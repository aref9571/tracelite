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
| `DatabaseConnectivityTests` | Integration, PostgreSQL | The backend connects to PostgreSQL 17; Flyway uses the expected location, has `clean` disabled, and has no pending migrations; a clean database is migrated to `V1` with the `issues` and `events` tables |
| `IssueTest` | Domain unit | New issues start unresolved with one occurrence; valid and invalid status transitions; field validation and length limits |
| `ErrorEventTest` | Domain unit | Required fields, length limits, optional fields, and that event context is copied and immutable |
| `IssueRepositoryTests` | Repository, PostgreSQL | Issues round-trip; lookup by scoped fingerprint; duplicate scoped fingerprints are rejected; the same fingerprint is allowed in another environment; status changes persist and bump the version; stale updates fail with an optimistic-locking error; status changes never overwrite counters; `recordOccurrence` increments the count, never moves last-seen backwards, leaves the version alone, and updates nothing for an unknown issue |
| `ErrorEventRepositoryTests` | Repository, PostgreSQL | Events round-trip with their issue and `jsonb` context; an issue's events page newest first with a deterministic order; deleting an issue deletes its events |
| `SchemaConstraintTests` | Schema, PostgreSQL | Each check constraint, the foreign key and the `jsonb` object rule reject invalid rows written with plain SQL, bypassing the Java validation |
| `FingerprintGeneratorTest` | Unit | The placeholder fingerprint is 64 lowercase hex characters, deterministic (checked against a known SHA-256 value), and differs per exception type |
| `EventIngestionServiceTest` | Service unit, Mockito, fixed clock | A new fingerprint creates an issue seen at `receivedAt`; a known one records an occurrence instead; the event stores the client's `occurredAt` and the server's `receivedAt`; `occurredAt` at the edges of the allowed range is accepted and just outside is rejected; null characters in text fields and nested in `context` are rejected before anything is saved |
| `EventIngestionControllerTests` | HTTP, `@WebMvcTest` with a mocked service | `201` with the response body and the mapped submission; only required fields; missing fields give sorted field errors; every length limit; identifier rules; too many `context` entries; malformed, non-object and wrongly typed JSON give `400` without parser details; empty body; `415`; `405`; `413` for a large `Content-Length`; service validation errors; `503` for an unreachable database and `500` for other failures, without internal details |
| `RequestSizeLimitFilterTest` | Unit, servlet mocks | A body at the limit passes; a body without `Content-Length` fails once it passes the limit; a large declared `Content-Length` gets `413` without reaching the application; non-API paths are not limited |
| `EventIngestionIntegrationTests` | HTTP integration, PostgreSQL | A valid event is stored with its issue and `jsonb` context; repeated events join one issue and update its count and last-seen; environments and exception types get separate issues; invalid, future-dated and null-character events are rejected and nothing is stored; an oversized chunked body gets `413` from the real server |
| `EventIngestionRollbackTests` | HTTP integration, PostgreSQL, Mockito spy | When saving the event fails, the client gets `500` and the occurrence-count update is rolled back |
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
