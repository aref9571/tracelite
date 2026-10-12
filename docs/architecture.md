# Architecture

## Overview

TraceLite is a modular monolith: one Spring Boot application, one PostgreSQL database, and a separately built React dashboard. See [ADR 0001](decisions/0001-monolithic-architecture.md) for the reasoning.

```
 Client applications ──► POST /api/v1/events ─┐
                                              ▼
                                 ┌─────────────────────────┐
 Dashboard (React) ─► /api/v1 ─► │  TraceLite backend      │ ──► PostgreSQL
                                 │  (Spring Boot)          │
                                 └─────────────────────────┘
```

Today the backend connects to PostgreSQL, runs Flyway on startup, validates the JPA mapping against the schema, and exposes health probes. It accepts error events at `POST /api/v1/events`, stores them and attaches them to issues. The dashboard, the issue management endpoints and authentication are not built yet; the diagram describes the target design.

## Build layout

The repository uses a Maven multi-module layout:

- `pom.xml` (root, `tracelite-parent`) inherits `spring-boot-starter-parent`, declares shared properties (Java 21, encoding), enforces the minimum Java and Maven versions, and aggregates the modules.
- `backend/pom.xml` (`tracelite-backend`) is the only Java module and contains the Spring Boot application.
- `frontend/` is built with npm and Vite, not Maven.

The Maven Wrapper lives at the root, so every build command runs from the repository root. The multi-module root keeps backend build configuration separate from repository-level files and gives one place for shared build rules, without introducing extra Java modules.

Spring Boot is pinned to 3.5.16, the latest 3.x release line, as required by the project specification. Open-source support for Spring Boot 3.5 ended in June 2026; migrating to Spring Boot 4 is a future consideration documented here rather than taken on during the initial release.

## Package structure

Code under `backend/src/main/java/com/tracelite` is organised by feature, with layers inside each feature:

| Package | Responsibility |
|---|---|
| `config` | Spring configuration and typed properties |
| `security` | API-key authentication |
| `common.exception` | Shared exception types and centralised error handling |
| `common.web` | Shared web concerns such as pagination and error response DTOs |
| `event.api` / `issue.api` | Controllers and request/response DTOs |
| `event.application` / `issue.application` | Use cases and transaction boundaries |
| `event.domain` / `issue.domain` | Entities, value objects, and business rules |
| `event.persistence` / `issue.persistence` | Spring Data repositories |

### Layering rules

- Controllers translate HTTP to application calls; they contain no queries or business rules.
- Application services own transactions and orchestrate domain objects and repositories.
- Domain code does not depend on web or persistence frameworks beyond JPA mapping.
- Repositories never decide HTTP status codes.
- Interfaces are introduced only where they add a real boundary or testing benefit.

## Domain model

The domain has two entities, mapped with JPA. The full schema is in [database.md](database.md).

- **`Issue`** (`issue.domain`): a group of similar errors from one application and environment, identified by a fingerprint. It holds the lifecycle status, the occurrence count, and the first- and last-seen times.
- **`ErrorEvent`** (`event.domain`): one received error, belonging to exactly one issue. Events are immutable once stored.

Constructors validate required fields and the same length limits the database enforces, so an invalid entity cannot be created in memory. Entities have no setters; state changes go through domain methods.

### Issue lifecycle

```
            resolve(at)
 UNRESOLVED ──────────► RESOLVED
      ▲                     │
      └─────── reopen() ────┘
```

| From | To | Operation | Effect |
|---|---|---|---|
| (new) | `UNRESOLVED` | constructor | Occurrence count 1, first and last seen set |
| `UNRESOLVED` | `RESOLVED` | `resolve(at)` | Records `resolvedAt` |
| `RESOLVED` | `UNRESOLVED` | `reopen()` | Clears `resolvedAt` |

Resolving a resolved issue or reopening an unresolved one throws `InvalidIssueStatusTransitionException` and leaves the issue unchanged; the API will map it to `409 Conflict` when the lifecycle endpoints are added on Day 7. The database repeats the rule that `resolved_at` is set exactly when the status is `RESOLVED`.

Whether a new event for a resolved issue reopens it automatically (a regression) is decided with grouping on Day 5. Until then, ingestion counts the event and leaves the status unchanged.

### Ownership of fields

- Identity and descriptive fields (application, environment, fingerprint, exception type, first message, first seen) are set once and never updated.
- `status` and `resolvedAt` change only through the lifecycle methods and are protected by optimistic locking (`@Version`).
- `occurrenceCount` and `lastSeenAt` are mapped as non-updatable. Ingestion changes them with one atomic SQL update (`IssueRepository.recordOccurrence`), so saving an entity can never overwrite a newer count.

## Configuration

Configuration comes from `application.yml`, overridden by environment variables through Spring Boot's relaxed binding (for example, `SPRING_DATASOURCE_URL` sets `spring.datasource.url`). Secrets are never stored in the repository. Variables are documented in `.env.example` and [development.md](development.md#configuration).

- The **default profile** is the production-like baseline. Every environment-specific value must be supplied, and the application fails fast at startup when database settings are missing.
- The **`local` profile** only adds developer conveniences: verbose logging and detailed health output.
- **Tests** get their database from Testcontainers through `@ServiceConnection`, not from a test profile. The master plan lists an `application-test.yml` in `src/main/resources`; it is deliberately not used, because test settings should not ship inside the production jar.

## Runtime lifecycle

1. Spring Boot binds configuration and creates the HikariCP pool.
2. Flyway connects to PostgreSQL and applies pending migrations. A failure here stops startup.
3. The embedded Tomcat server starts, and readiness reports `UP` once the database health check passes.
4. On `SIGTERM`, the server stops accepting new requests and waits up to 20 seconds for in-flight requests before shutting down.

Liveness (`/actuator/health/liveness`) reflects only the application's internal state, so a database outage makes the instance not ready without making it look broken.

## Event ingestion

`POST /api/v1/events` is handled synchronously ([ADR 0003](decisions/0003-event-ingestion.md)):

1. `RequestSizeLimitFilter` (`common.web`) rejects bodies over the configured limit with `413`.
2. `EventIngestionController` (`event.api`) parses the JSON into `IngestEventRequest`, validates it with Bean Validation, and maps it to an `ErrorEventSubmission`.
3. `EventIngestionService` (`event.application`) runs in one transaction. It takes `receivedAt` from the injected `Clock`, checks `occurredAt` against the allowed range and rejects null characters, asks `FingerprintGenerator` (`issue.application`) for the fingerprint, then either creates the issue or records an occurrence on it, and saves the `ErrorEvent`.
4. The controller returns `201` with the event ID, issue ID and `receivedAt` only after the transaction commits. If anything fails, the transaction rolls back and the client gets an error status, never `201`.

`FingerprintGenerator` is isolated so that Day 5 can replace its placeholder algorithm (a hash of the exception type) without touching the ingestion flow. Limits live in `IngestionProperties` (`tracelite.ingestion.*`), and the clock is a bean so tests can fix the time.

## Error handling

`GlobalExceptionHandler` (`common.exception`) extends Spring's `ResponseEntityExceptionHandler`, so every error from a controller, including Spring MVC's own (`404`, `405`, `415`, malformed JSON), becomes an RFC 9457 problem-details response with the same shape:

| Situation | Status | Title |
|---|---|---|
| Bean Validation failure, or `InvalidErrorEventException` from the service | `400` | Invalid request, with an `errors` list of `field` and `message` |
| Missing, unparseable or wrongly typed body | `400` | Malformed request |
| Body over the size limit | `413` | Request too large |
| Database unreachable or transient database failure (`CannotCreateTransactionException`, `DataAccessResourceFailureException`, `TransientDataAccessException`) | `503` | Service unavailable |
| Anything else, including other database errors | `500` | Internal server error |

Details are fixed strings, so internal exception messages, SQL and stack traces never reach clients. `503` and `500` are logged with the exception; validation failures are not logged. Spring Boot's own error page, used for errors outside controllers, is still configured never to include stack traces, exception class names or messages.
