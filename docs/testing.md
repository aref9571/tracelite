# Testing

## Running tests

```bash
./mvnw test      # all tests
./mvnw verify    # tests, packaging, coverage report and coverage check
```

Docker must be running: the integration tests start disposable PostgreSQL 17 containers with Testcontainers.

After `./mvnw verify`, the coverage report is in `backend/target/site/jacoco/index.html` (HTML), with `jacoco.xml` and `jacoco.csv` beside it.

## Coverage

JaCoCo 0.8.15 records coverage for every test, unit and integration alike, because they all run in the same Surefire execution. Nothing is excluded from the report.

`./mvnw verify` fails if the whole backend drops below **90% line** or **85% branch** coverage. These are floors that catch a large untested change, not targets: on Day 9 the backend was at 98.4% of lines (598 of 608) and 98.0% of branches (145 of 148). The floors are the `coverage.minimum.lines` and `coverage.minimum.branches` properties in `backend/pom.xml`.

Rules for coverage:

- Add a test because it checks behaviour that could break, never to raise the number. A test without a meaningful assertion is not acceptable.
- Do not exclude classes to pass the check. If the floor fails, add the missing tests or explain in review why the floor should change.
- Read the uncovered lines in the report, not just the percentage.

What remains uncovered, and why:

| Code | Reason |
|---|---|
| `TraceLiteApplication.main` | Started by the smoke test against the packaged jar, which JaCoCo does not instrument |
| `NoSuchAlgorithmException` handlers in `FingerprintGenerator` and `ApiKeyRegistry` | Every Java runtime must provide SHA-256, so the handler cannot run |
| `ApiSecurityProperties.toString` without a management key | Only changes `[REDACTED]` to `null` |

JaCoCo marks a line as missed when the method it calls throws, even though the line ran. A path that is only tested through an exception can therefore look uncovered; on Day 9 this hid the nested-list check in `EventIngestionService`, which now also has a test that accepts a valid nested list.

## Continuous integration

`.github/workflows/ci.yml` runs on every push and pull request:

1. `./mvnw -B -ntp verify` on JDK 21, including the Testcontainers tests and the coverage check.
2. The test totals and coverage are published as notices, and the HTML coverage report is uploaded as the `coverage-report` artifact (kept for 14 days).
3. `shellcheck` on the development and CI scripts.
4. `docker compose config` with a generated `.env` (random database password, management key and ingestion key).
5. `docker compose up --wait postgres`, using the real `docker-compose.yml`.
6. `scripts/development/smoke-test.sh` against that database.
7. `docker compose down --volumes`.

Test totals and coverage (`scripts/ci/summarize-coverage.sh`) are published as notices on the check run. On failure, Maven errors and failing Surefire reports are published as error annotations, so the cause is visible without opening the job log.

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
| `EventIngestionServiceTest` | Service unit, Mockito, fixed clock | The occurrence is recorded with the fingerprint of the event's type and stack trace at `receivedAt`, and the event is attached to the returned issue; the event stores the client's `occurredAt` and the server's `receivedAt`; `occurredAt` at the edges of the allowed range is accepted and just outside is rejected; null characters in text fields and nested in `context` are rejected before anything is saved; nested lists and maps without null characters are accepted |
| `EventIngestionControllerTests` | HTTP, `@WebMvcTest` with a mocked service and the real security configuration | `201` with the response body and the mapped submission; `403` when the ingestion key belongs to another application, without calling the service; only required fields; missing fields give sorted field errors; every length limit; identifier rules; too many `context` entries; malformed, non-object and wrongly typed JSON give `400` without parser details; empty body; `415`; `405`; `413` for a large `Content-Length`; service validation errors; `503` for an unreachable database and `500` for other failures, without internal details |
| `RequestSizeLimitFilterTest` | Unit, servlet mocks | A body at the limit passes; a body without `Content-Length` fails once it passes the limit, whether it is read in blocks or one byte at a time; the reader decodes the body with the declared character encoding; a large declared `Content-Length` gets `413` without reaching the application; non-API paths are not limited |
| `EventIngestionIntegrationTests` | HTTP integration, PostgreSQL | A valid event is stored with its issue and `jsonb` context; repeated events join one issue and update its count and last-seen; events differing only in line numbers and message join one issue; environments, exception types and throwing methods get separate issues; events without a stack trace group by type; a new event reopens a resolved issue; invalid, future-dated and null-character events are rejected and nothing is stored; an oversized chunked body gets `413` from the real server |
| `ConcurrentIngestionTests` | HTTP integration, PostgreSQL, concurrent | 12 simultaneous first events for one new fingerprint all get `201` with the same issue ID, and exactly one issue exists with a count of 12; 12 simultaneous events for an existing issue are all counted; 12 simultaneous events for a resolved issue are all counted and reopen it exactly once (version +1); resolve requests sent during ingestion get `200` or `409`, never an error, and no occurrence is lost. Threads are started together with latches, without sleeps |
| `EventIngestionRollbackTests` | HTTP integration, PostgreSQL, Mockito spy | When saving the event fails, the client gets `500` and the occurrence-count update is rolled back |
| `IssueSearchTests` | Repository and service, PostgreSQL | Default order is last seen, newest first; ties on the sort value are broken by ascending ID in both directions; paging through tied rows returns every issue once; sorting by first seen and occurrence count; pagination totals, the last partial page, a page past the end and an empty table; status and exact environment filters; filters combine with AND; the last-seen range includes its start and excludes its end; an empty or reversed range is rejected; search is case-insensitive over exception type and message, trims spaces and ignores blank text; `%`, `_` and `\` are matched literally; lookup by ID and not-found; the search query can use both trigram indexes |
| `IssueQueryServiceTest` | Service unit, Mockito | The page request carries the page, size and chosen sort, then `id` ascending; a reversed range is rejected before querying; lookup by ID returns the issue or throws not-found |
| `IssueControllerTests` | HTTP, `@WebMvcTest` with a mocked service and the real security configuration | Defaults when no parameters are given; every parameter, including a timestamp with an offset, reaches the service; the full response shape and pagination metadata; each invalid parameter gives `400` with one field error and no conversion details; overlong `environment` and `query`; a null character in `query`; the service's range error; detail by ID; `404` for an unknown ID; `400` for a non-UUID ID; resolve and reopen return the updated issue; `409` with the current status for a repeated request and for an optimistic-locking conflict, without internal details; `404` and `400` for lifecycle requests; `405` for `GET` on a lifecycle path |
| `IssueLifecycleServiceTest` | Service unit, Mockito, fixed clock | Resolve records the clock time truncated to microseconds and saves; reopen clears `resolvedAt`; resolving a resolved issue or reopening an unresolved one throws without saving and keeps `resolvedAt`; unknown IDs throw not-found |
| `IssueHistoryServiceTests` | Service, PostgreSQL, fixed clock | Events come back newest first with ties broken by descending ID; paging through seven events with tied times returns each once; an empty page past the end keeps the total; an issue with no events; statistics match SQL counts, including the exact 24-hour, 7-day and UTC-midnight boundaries, events just outside the 14-day window, and another issue's events; zero-filled statistics; not-found for both operations |
| `IssueHistoryControllerTests` | HTTP, `@WebMvcTest` with a mocked service and the real security configuration | Event fields including the stack trace, timestamps and `context`; default and explicit `page` and `size`; each invalid paging parameter gives `400`; `400` for a non-UUID ID; `404` for an unknown issue; the statistics response shape |
| `IssueLifecycleIntegrationTests` | HTTP integration, PostgreSQL, concurrent | Resolve and reopen through the real server, with `resolvedAt` and `version` checked in the database; repeated resolve and reopen give `409` and change nothing; a new event reopens a resolved issue; event history is newest first and pages match the database order; statistics equal the stored counts; `404` for unknown issues; 8 simultaneous resolves give exactly one `200` and seven `409`, and the version increases once; the OpenAPI document lists the new endpoints |
| `IssueApiIntegrationTests` | HTTP integration, PostgreSQL | Issues created through ingestion are listed newest first with correct totals, filtered by environment, status and text, and returned by ID with their count; a URL-encoded timestamp with an offset is accepted; `404` and `400` problem details from the real server; the OpenAPI document lists the event and issue endpoints |
| `HealthEndpointTests` | HTTP integration, PostgreSQL | Health, liveness and readiness return `200` with `UP` without a key; health hides component details; `env`, `configprops`, `beans` and `heapdump` are unreachable (`401` without a key, `403` with the management key) |
| `ApiKeyRegistryTest` | Unit | The management key gets only the management authority and no stored credentials; an ingestion key authenticates as its application; two keys for one application both work (rotation); missing, empty, malformed, case-changed, truncated and unknown keys are rejected; a reused or malformed configured key fails without revealing the key; no keys or a blank management key means nothing is accepted |
| `SecurityConfigurationTest` | Unit, captured output | A warning is logged when no API keys are configured, and not when a key is |
| `ApiSecurityPropertiesTests` | Spring context runner | Keys for several applications and origins bind from properties; the backend starts without keys; malformed management keys, a project without a key, an invalid application name, wildcard or path origins and duplicate keys stop startup; the failure and the properties' `toString` never contain a key |
| `ApiAccessControlTests` | HTTP, `@WebMvcTest` with all controllers and mocked services | Every protected endpoint answers `401` with `WWW-Authenticate` and problem details when the key is missing, malformed, too long, unknown, sent twice, sent as a bearer token or differs only in case; ingestion keys get `403` on every management endpoint and the management key `403` on ingestion; unmapped paths are denied; security headers are set and no session cookie; cross-origin requests get no CORS headers by default; no service is called in any of these cases |
| `CorsConfigurationTests` | HTTP, `@WebMvcTest` with an allowed origin | A preflight from the configured origin succeeds without a key and allows `X-API-Key` without credentials; another origin and a disallowed method get `403`; a cross-origin request still needs a key |
| `ApiSecurityIntegrationTests` | HTTP integration, PostgreSQL, captured output | An ingestion key stores an event that the management key can read and resolve; rejected ingestion requests (no key, unknown key, management key, another application's key) store nothing; an ingestion key cannot read or resolve issues; an unauthenticated oversized body gets `401`; health, the OpenAPI document and Swagger UI are public; the OpenAPI document declares the `X-API-Key` scheme; with `DEBUG` and Spring Security `TRACE` logging, no key ever appears in the logs |

Repository and schema tests use `@DataJpaTest` against the Testcontainers database, and each test rolls back its transaction. HTTP integration tests extend `ApiIntegrationTestSupport`, which starts a real server on a random port, empties the tables before each test, and provides the shared helpers: posting events with an ingestion key, calling management endpoints with the management key, counting rows, and starting requests at the same moment with latches.

`PostgresTestcontainersConfiguration` provides the PostgreSQL container and wires it to Spring Boot with `@ServiceConnection`. `src/test/resources/config/application.yml` configures test-only API keys (a management key and ingestion keys for `checkout-service` and `billing-service`), and `TestApiKeys` holds the same values for requests. Controller tests import the real `SecurityConfiguration` and send these keys, so they exercise the same access rules as production.

## Transaction boundaries

Transactions start in the application services, never in controllers, and open-in-view is disabled.

| Service | Transaction | Verified by |
|---|---|---|
| `EventIngestionService.ingest` | Read-write; the occurrence upsert and the event insert commit together | `EventIngestionRollbackTests` (a failed insert rolls back the count), `ConcurrentIngestionTests` |
| `IssueLifecycleService.resolve` / `reopen` | Read-write; `saveAndFlush` runs the version check inside the transaction | `IssueLifecycleServiceTest`, `IssueRepositoryTests`, simultaneous resolves in `IssueLifecycleIntegrationTests` and `ConcurrentIngestionTests` |
| `IssueQueryService` | Read-only | `IssueSearchTests` |
| `IssueHistoryService` | Read-only, `REPEATABLE READ`, so a page and its total, and all statistics, come from one snapshot | `IssueHistoryServiceTests` |

## Smoke test

`scripts/development/smoke-test.sh` exercises the packaged jar as an operator would: startup without database configuration or with a wrong password must fail with a clear message, health probes must report `UP`, the API must reject missing, wrong and misused API keys and accept the right ones, and `SIGTERM` must produce a graceful shutdown. See [development.md](development.md#smoke-test).

## Strategy

As features are added, tests are split by what they verify:

- **Domain unit tests:** business rules such as fingerprinting and issue status transitions, without Spring.
- **Service tests:** application logic, using Mockito only where isolation is useful.
- **HTTP tests:** controller behaviour, validation and error responses through MockMvc.
- **Persistence and integration tests:** repositories, constraints, transactions and concurrent ingestion against real PostgreSQL via Testcontainers.
- **Security tests:** rejection of missing, malformed and invalid credentials.

Tests use deterministic data and a controlled clock where time matters, and never rely on sleeps or execution order.
