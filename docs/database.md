# Database

## Engine

PostgreSQL 17 is the only supported database. Local development uses the `postgres:17-alpine` image from `docker-compose.yml`; the integration tests use the same image through Testcontainers.

## Connection

The backend connects through HikariCP using the `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD` environment variables. There are no default credentials. The pool is named `tracelite-db` and waits at most 10 seconds for a connection. See [development.md](development.md#configuration).

## Migrations

Flyway manages the schema:

- Migrations live in `backend/src/main/resources/db/migration` and are named `V<n>__<description>.sql`.
- Flyway runs on application startup, before the application accepts traffic.
- `validate-on-migrate` is enabled, so a changed or missing applied migration stops startup.
- `clean` is disabled, so Flyway can never drop the schema.
- Merged migrations are never edited; changes go in a new migration.

| Version | File | Purpose |
|---|---|---|
| 1 | `V1__create_issues_and_events.sql` | Creates the `issues` and `events` tables with their constraints and indexes |

## Conventions

- **Identifiers:** every table uses a `uuid` primary key. Hibernate generates the UUID when an entity is persisted; the column also defaults to `gen_random_uuid()` so rows inserted with plain SQL get one too. Client applications never choose identifiers.
- **Timestamps:** stored as `timestamptz` and mapped to `java.time.Instant`. Hibernate writes them in UTC. PostgreSQL keeps microsecond precision.
- **Names:** tables and columns are plural `snake_case`. Constraints are named `<table>_<purpose>_<pk|uk|fk|ck>` and indexes `<table>_<columns>_idx`, so a violation in a log or test names the rule that was broken.
- **Schema ownership:** Flyway owns the schema. Hibernate runs with `ddl-auto: validate`, so startup fails if an entity no longer matches the tables.
- **Text limits:** the same limits are enforced in the domain constructors and in the database: application 100, environment 50, exception type 255, message 4000, correlation ID 128 and stack trace 65,536 characters.

## Schema

```
issues 1 ──────< events
  id (pk)          id (pk)
  application      issue_id (fk → issues.id, on delete cascade)
  environment      exception_type
  fingerprint      message
  exception_type   stack_trace
  message          occurred_at
  status           received_at
  occurrence_count correlation_id
  first_seen_at    context (jsonb)
  last_seen_at
  resolved_at
  version
```

### `issues`

One row per group of similar errors in one application and environment.

| Column | Type | Rules |
|---|---|---|
| `id` | `uuid` | Primary key |
| `application` | `varchar(100)` | Required, not blank |
| `environment` | `varchar(50)` | Required, not blank |
| `fingerprint` | `varchar(64)` | Required, 64 lowercase hex characters (a SHA-256 digest; the algorithm is defined on Day 5) |
| `exception_type` | `varchar(255)` | Required, not blank |
| `message` | `varchar(4000)` | Optional; the message of the first event |
| `status` | `varchar(16)` | `UNRESOLVED` or `RESOLVED` |
| `occurrence_count` | `bigint` | At least 1 |
| `first_seen_at`, `last_seen_at` | `timestamptz` | Required; `last_seen_at >= first_seen_at` |
| `resolved_at` | `timestamptz` | Set exactly when `status = 'RESOLVED'` |
| `version` | `bigint` | Optimistic-locking version, incremented on every entity update |

`issues_scope_fingerprint_uk` makes `(application, environment, fingerprint)` unique. This is what prevents two concurrent requests from creating duplicate issues for the same failure; ingestion relies on it. Day 4 ingestion looks the issue up and inserts it if missing; handling two first events for the same new fingerprint at the same moment is Day 5 work.

### `events`

One row per error received. Events are immutable after insert.

| Column | Type | Rules |
|---|---|---|
| `id` | `uuid` | Primary key |
| `issue_id` | `uuid` | Required, references `issues.id` |
| `exception_type` | `varchar(255)` | Required, not blank |
| `message` | `varchar(4000)` | Optional |
| `stack_trace` | `text` | Optional, at most 65,536 characters |
| `occurred_at` | `timestamptz` | Required; the time reported by the client |
| `received_at` | `timestamptz` | Required; set by the server |
| `correlation_id` | `varchar(128)` | Optional request or trace identifier |
| `context` | `jsonb` | Optional; must be a JSON object |

Application and environment are not repeated on events; they come from the event's issue.

### Indexes

| Index | Supports |
|---|---|
| `issues_scope_fingerprint_uk` | Looking up the issue for an incoming event |
| `issues_last_seen_idx` | Listing issues by most recent activity |
| `issues_status_last_seen_idx` | Listing issues filtered by status |
| `issues_environment_last_seen_idx` | Listing issues filtered by environment |
| `events_issue_received_idx` | Paging through an issue's events, newest first |

Every listing index ends with `id` so pagination has a deterministic tie-breaker. Indexes for message search are deferred to Day 6, when the search behaviour is defined.

### Deletion policy

Deleting an issue deletes its events (`on delete cascade`). An event never exists without its issue. No API deletes data yet; retention is a later decision.

## Concurrency

- **Issue creation:** the unique scoped fingerprint is the guard against duplicate issues.
- **Status changes:** `issues.version` is mapped with JPA `@Version`, so a resolve or reopen based on a stale read fails with an optimistic-locking error instead of overwriting another change.
- **Occurrence counters:** ingestion updates `occurrence_count` and `last_seen_at` with one atomic SQL statement, `IssueRepository.recordOccurrence`: `occurrence_count = occurrence_count + 1, last_seen_at = GREATEST(last_seen_at, :seenAt)`. There is no read-modify-write in Java, so concurrent events for an existing issue never lose an increment, and `last_seen_at` never moves backwards. The statement does not change `version`, so counting events never makes a concurrent resolve fail. Both columns are mapped as non-updatable, so saving an `Issue` entity (for example after a status change) can never write back a stale count. See [ADR 0003](decisions/0003-event-ingestion.md).
