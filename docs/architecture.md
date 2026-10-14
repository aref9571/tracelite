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

Today the backend connects to PostgreSQL, runs Flyway on startup, validates the JPA mapping against the schema, and exposes health probes. It accepts error events at `POST /api/v1/events`, stores them and attaches them to issues, and lets clients list, filter, search and read issues. The dashboard, event history, the resolve and reopen endpoints and authentication are not built yet; the diagram describes the target design.

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
| `event.persistence` / `issue.persistence` | Spring Data repositories and query specifications |

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

Resolving a resolved issue or reopening an unresolved one throws `InvalidIssueStatusTransitionException` and leaves the issue unchanged; the API maps it to `409 Conflict` ([ADR 0004](decisions/0004-issue-lifecycle-api.md)). The database repeats the rule that `resolved_at` is set exactly when the status is `RESOLVED`.

A new event for a resolved issue reopens it automatically, because the failure has come back. Ingestion does this in SQL, not through `reopen()`, as part of the statement that counts the event, and increments `version` when it does, so a lifecycle change based on an older read fails instead of overwriting the reopen ([ADR 0003](decisions/0003-event-ingestion.md)).

### Ownership of fields

- Identity and descriptive fields (application, environment, fingerprint, exception type, first message, first seen) are set once and never updated.
- `status` and `resolvedAt` change only through the lifecycle methods and are protected by optimistic locking (`@Version`).
- `occurrenceCount` and `lastSeenAt` are mapped as non-updatable. Ingestion changes them with one atomic SQL statement (`IssueRepository.recordOccurrence`), so saving an entity can never overwrite a newer count.

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

## Authentication and access control

Every request passes Spring Security's filter chain before any controller or the request-size filter ([ADR 0005](decisions/0005-api-key-authentication.md)):

1. `ApiKeyAuthenticationFilter` (`security`) reads the `X-API-Key` header. When exactly one key is present, `ApiKeyRegistry` hashes it with SHA-256 and compares it in constant time with the hash of every configured key. A match sets the authentication: the management key gets the `MANAGE_ISSUES` authority, an ingestion key gets `INGEST_EVENTS` with an `IngestionClient` principal naming its application. A missing or unknown key leaves the request anonymous.
2. The authorization rules in `SecurityConfiguration` then decide: health, info, the OpenAPI document and Swagger UI are public; `/api/v1/events` needs `INGEST_EVENTS`; `/api/v1/issues/**` needs `MANAGE_ISSUES`; anything else is denied. An anonymous request to a protected path gets `401`, an authenticated one without the authority `403`, both written by `SecurityProblemResponder` as problem details.
3. CORS is handled before authentication, so a browser preflight from an origin in `tracelite.security.allowed-origins` succeeds without a key. With no origins configured, no CORS headers are sent.

The chain is stateless: no session, cookie or CSRF token is created. Keys are read once at startup from `ApiSecurityProperties` (`tracelite.security.*`); only their hashes stay in memory, and the properties' `toString` redacts them.

## Event ingestion

`POST /api/v1/events` is handled synchronously ([ADR 0003](decisions/0003-event-ingestion.md)):

1. The security filter chain rejects requests without a valid ingestion key (`401`, or `403` for the management key) before the body is read.
2. `RequestSizeLimitFilter` (`common.web`) rejects bodies over the configured limit with `413`.
3. `EventIngestionController` (`event.api`) parses the JSON into `IngestEventRequest`, validates it with Bean Validation, checks that its `application` is the one the ingestion key belongs to (`403` otherwise), and maps it to an `ErrorEventSubmission`.
4. `EventIngestionService` (`event.application`) runs in one transaction. It takes `receivedAt` from the injected `Clock`, checks `occurredAt` against the allowed range and rejects null characters, asks `FingerprintGenerator` (`issue.application`) for the fingerprint, calls `IssueRepository.recordOccurrence`, which creates the issue or counts the occurrence in one `INSERT ... ON CONFLICT` statement and returns the issue ID, and saves the `ErrorEvent`.
5. The controller returns `201` with the event ID, issue ID and `receivedAt` only after the transaction commits. If anything fails, the transaction rolls back and the client gets an error status, never `201`.

`FingerprintGenerator` hashes the exception type and up to 30 normalised stack frames ([ADR 0002](decisions/0002-issue-fingerprinting.md)). It is a plain component with no state, so the algorithm can change without touching the ingestion flow. Limits live in `IngestionProperties` (`tracelite.ingestion.*`), and the clock is a bean so tests can fix the time.

## Issue queries

`GET /api/v1/issues` and `GET /api/v1/issues/{id}` are read-only:

1. `IssueController` (`issue.api`) binds the query parameters to `IssueSearchRequest` and validates them with Bean Validation. It fills in the defaults (sort by last seen, newest first, page 0, 20 per page) and passes an `IssueSearchCriteria` to the service.
2. `IssueQueryService` (`issue.application`) runs in a read-only transaction. It rejects a date range whose end is not after its start, builds one JPA `Specification` per filter that is set (`IssueSpecifications` in `issue.persistence`), and asks `IssueRepository` for one page sorted by the chosen field and then by `id`. Spring Data runs the page query and a matching `count` query.
3. The controller maps each `Issue` to `IssueResponse` and the page to `PageResponse` (`common.web`), so the JSON shape does not depend on Spring Data's `Page` class or on the entity.

Text search lowercases the input, escapes the `LIKE` wildcards `%`, `_` and `\`, and matches it anywhere in `lower(exception_type)` or `lower(message)`, which the trigram indexes from `V2` support ([database.md](database.md#indexes)). Every value is a bound parameter.

The query endpoints return the stored issue fields.

## Issue lifecycle operations

`POST /api/v1/issues/{id}/resolve` and `/reopen` go through `IssueLifecycleService` (`issue.application`). Each call runs in one transaction: it loads the issue, calls `resolve(at)` or `reopen()` on the entity, and saves it with `saveAndFlush`, so the version check runs inside the service and a lost race surfaces as an optimistic-locking exception before the response is built. `resolvedAt` comes from the injected `Clock`, truncated to microseconds to match PostgreSQL. Each change is logged at `INFO` with the issue ID only. The rules, repeated requests and concurrency are recorded in [ADR 0004](decisions/0004-issue-lifecycle-api.md).

## Event history and statistics

`GET /api/v1/issues/{id}/events` and `/stats` are served by `IssueHistoryController` (`event.api`) and `IssueHistoryService` (`event.application`). They live in the event feature because they read events; the event feature already depends on the issue feature, and the issue feature does not depend on events.

- **Event history** checks that the issue exists (`404` otherwise) and asks `ErrorEventRepository` for one page sorted by `receivedAt` and then `id`, both descending. This order matches the `events_issue_received_idx` index. `EventResponse` maps every stored field except the issue reference.
- **Statistics** read the issue, count its stored events, count events received in the last 24 hours and 7 days with derived Spring Data queries, and group the last 14 UTC days with one native query (`to_char(received_at AT TIME ZONE 'UTC', 'YYYY-MM-DD')`). The service fills days without events with zero, so the response always has 14 entries.

Both methods run in read-only transactions with `REPEATABLE READ` isolation. In PostgreSQL that gives every statement in the transaction the same snapshot, so a page and its total, or the counts in one statistics response, never disagree because an event arrived between two queries.

## Error handling

`GlobalExceptionHandler` (`common.exception`) extends Spring's `ResponseEntityExceptionHandler`, so every error from a controller, including Spring MVC's own (`404`, `405`, `415`, malformed JSON), becomes an RFC 9457 problem-details response with the same shape:

| Situation | Status | Title |
|---|---|---|
| Bean Validation failure, a query or path parameter that cannot be converted, or `InvalidErrorEventException` or `InvalidIssueQueryException` from a service | `400` | Invalid request, with an `errors` list of `field` and `message` |
| `IssueNotFoundException` | `404` | Issue not found |
| `InvalidIssueStatusTransitionException` (resolving a resolved issue or reopening an unresolved one) | `409` | Invalid status transition, with `currentStatus` and `requestedStatus` |
| Optimistic-locking failure (another request changed the row first) | `409` | Concurrent modification |
| Missing, malformed or unknown API key on a protected path (written by `SecurityProblemResponder`, with `WWW-Authenticate`) | `401` | Unauthorized |
| Valid API key on a path it may not use (`SecurityProblemResponder`), or an ingestion key used for another application (`ApplicationAccessDeniedException`) | `403` | Forbidden |
| Missing, unparseable or wrongly typed body | `400` | Malformed request |
| Body over the size limit | `413` | Request too large |
| Database unreachable or transient database failure (`CannotCreateTransactionException`, `DataAccessResourceFailureException`, `TransientDataAccessException`) | `503` | Service unavailable |
| Anything else, including other database errors | `500` | Internal server error |

Details are fixed strings, and conversion errors say only `has an invalid value`, so internal exception messages, SQL and stack traces never reach clients. `503` and `500` are logged with the exception; validation failures and transition conflicts are not logged, and optimistic-locking conflicts are logged at `INFO` without details. The optimistic-locking handler is listed separately because Spring's `OptimisticLockingFailureException` is a `TransientDataAccessException`, which would otherwise be reported as `503`. Spring Boot's own error page, used for errors outside controllers, is still configured never to include stack traces, exception class names or messages.
