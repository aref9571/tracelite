# Testing

## Running tests

```bash
./mvnw test      # all tests
./mvnw verify    # tests plus packaging
```

Docker must be running: the integration tests start disposable PostgreSQL 17 containers with Testcontainers.

## Continuous integration

`.github/workflows/ci.yml` runs on every push and pull request:

1. `./mvnw -B -ntp verify` on JDK 21, including the Testcontainers tests.
2. `shellcheck` on the development and CI scripts.
3. `docker compose config` with a generated `.env`.
4. `docker compose up --wait postgres`, using the real `docker-compose.yml`.
5. `scripts/development/smoke-test.sh` against that database.
6. `docker compose down --volumes`.

Test totals are published as a notice on the check run. On failure, Maven errors and failing Surefire reports are published as error annotations, so the cause is visible without opening the job log.

## Current tests

| Test | Type | Verifies |
|---|---|---|
| `TraceLiteApplicationTests` | Spring Boot context, PostgreSQL | The application context starts |
| `DatabaseConnectivityTests` | Integration, PostgreSQL | The backend connects to PostgreSQL 17; Flyway uses the expected location, has `clean` disabled, and has no pending migrations; a clean database is migrated to `V2` with the `issues` and `events` tables and the `pg_trgm` extension |
| `IssueTest` | Domain unit | New issues start unresolved with one occurrence; valid and invalid status transitions; field validation and length limits |
| `ErrorEventTest` | Domain unit | Required fields, length limits, optional fields, and that event context is copied and immutable |
| `IssueRepositoryTests` | Repository, PostgreSQL | Issues round-trip; lookup by scoped fingerprint; duplicate scoped fingerprints are rejected; the same fingerprint is allowed in another environment; status changes persist and bump the version; stale updates fail with an optimistic-locking error; status changes never overwrite counters; `recordOccurrence` creates an issue for a new scoped fingerprint, increments the count and keeps the first message for a known one, never moves last-seen backwards, keeps environments separate, leaves the version alone unless it reopens a resolved issue, and makes a status change based on an older read fail |
| `ErrorEventRepositoryTests` | Repository, PostgreSQL | Events round-trip with their issue and `jsonb` context; an issue's events page newest first with a deterministic order; deleting an issue deletes its events |
| `SchemaConstraintTests` | Schema, PostgreSQL | Each check constraint, the foreign key and the `jsonb` object rule reject invalid rows written with plain SQL, bypassing the Java validation |
| `FingerprintGeneratorTest` | Unit | The fingerprint is 64 lowercase hex characters and matches a fixed SHA-256 value; it ignores the message, line numbers, file names, module and class-loader prefixes, whitespace and line endings, numbers in lambda, proxy, accessor, CGLIB and Hibernate names, recursion depth, frames beyond the limit, causes, suppressed exceptions and a truncated last frame; it falls back to the exception type for missing, blank and non-Java traces; it differs for another exception type, throwing method, caller or frame order |
| `EventIngestionServiceTest` | Service unit, Mockito, fixed clock | The occurrence is recorded with the fingerprint of the event's type and stack trace at `receivedAt`, and the event is attached to the returned issue; the event stores the client's `occurredAt` and the server's `receivedAt`; `occurredAt` at the edges of the allowed range is accepted and just outside is rejected; null characters in text fields and nested in `context` are rejected before anything is saved |
| `EventIngestionControllerTests` | HTTP, `@WebMvcTest` with a mocked service | `201` with the response body and the mapped submission; only required fields; missing fields give sorted field errors; every length limit; identifier rules; too many `context` entries; malformed, non-object and wrongly typed JSON give `400` without parser details; empty body; `415`; `405`; `413` for a large `Content-Length`; service validation errors; `503` for an unreachable database and `500` for other failures, without internal details |
| `RequestSizeLimitFilterTest` | Unit, servlet mocks | A body at the limit passes; a body without `Content-Length` fails once it passes the limit; a large declared `Content-Length` gets `413` without reaching the application; non-API paths are not limited |
| `EventIngestionIntegrationTests` | HTTP integration, PostgreSQL | A valid event is stored with its issue and `jsonb` context; repeated events join one issue and update its count and last-seen; events differing only in line numbers and message join one issue; environments, exception types and throwing methods get separate issues; events without a stack trace group by type; a new event reopens a resolved issue; invalid, future-dated and null-character events are rejected and nothing is stored; an oversized chunked body gets `413` from the real server |
| `ConcurrentIngestionTests` | HTTP integration, PostgreSQL, concurrent | 12 simultaneous first events for one new fingerprint all get `201` with the same issue ID, and exactly one issue exists with a count of 12; 12 simultaneous events for an existing issue are all counted. Threads are started together with latches, without sleeps |
| `EventIngestionRollbackTests` | HTTP integration, PostgreSQL, Mockito spy | When saving the event fails, the client gets `500` and the occurrence-count update is rolled back |
| `IssueSearchTests` | Repository and service, PostgreSQL | Default order is last seen, newest first; ties on the sort value are broken by ascending ID in both directions; paging through tied rows returns every issue once; sorting by first seen and occurrence count; pagination totals, the last partial page, a page past the end and an empty table; status and exact environment filters; filters combine with AND; the last-seen range includes its start and excludes its end; an empty or reversed range is rejected; search is case-insensitive over exception type and message, trims spaces and ignores blank text; `%`, `_` and `\` are matched literally; lookup by ID and not-found; the search query can use both trigram indexes |
| `IssueQueryServiceTest` | Service unit, Mockito | The page request carries the page, size and chosen sort, then `id` ascending; a reversed range is rejected before querying; lookup by ID returns the issue or throws not-found |
| `IssueControllerTests` | HTTP, `@WebMvcTest` with a mocked service | Defaults when no parameters are given; every parameter, including a timestamp with an offset, reaches the service; the full response shape and pagination metadata; each invalid parameter gives `400` with one field error and no conversion details; overlong `environment` and `query`; a null character in `query`; the service's range error; detail by ID; `404` for an unknown ID; `400` for a non-UUID ID |
| `IssueApiIntegrationTests` | HTTP integration, PostgreSQL | Issues created through ingestion are listed newest first with correct totals, filtered by environment, status and text, and returned by ID with their count; a URL-encoded timestamp with an offset is accepted; `404` and `400` problem details from the real server; the OpenAPI document lists the event and issue endpoints |
| `HealthEndpointTests` | HTTP integration, PostgreSQL | Health, liveness and readiness return `200` with `UP`; health hides component details; `env`, `configprops`, `beans` and `heapdump` are not exposed |

Repository and schema tests use `@DataJpaTest` against the Testcontainers database, and each test rolls back its transaction. HTTP integration tests use a real server on a random port and empty the tables before each test.

`PostgresTestcontainersConfiguration` provides the PostgreSQL container and wires it to Spring Boot with `@ServiceConnection`.

## Smoke test

`scripts/development/smoke-test.sh` exercises the packaged jar as an operator would: startup without database configuration or with a wrong password must fail with a clear message, health probes must report `UP`, and `SIGTERM` must produce a graceful shutdown. See [development.md](development.md#smoke-test).

## Strategy

As features are added, tests are split by what they verify:

- **Domain unit tests:** business rules such as fingerprinting and issue status transitions, without Spring.
- **Service tests:** application logic, using Mockito only where isolation is useful.
- **HTTP tests:** controller behaviour, validation and error responses through MockMvc.
- **Persistence and integration tests:** repositories, constraints, transactions and concurrent ingestion against real PostgreSQL via Testcontainers.
- **Security tests:** rejection of missing, malformed and invalid credentials.

Tests use deterministic data and a controlled clock where time matters, and never rely on sleeps or execution order. JaCoCo coverage reporting is added on Day 9.
