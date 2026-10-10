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
| `DatabaseConnectivityTests` | Integration, PostgreSQL | The backend connects to PostgreSQL 17; Flyway uses the expected location, has `clean` disabled, and has no pending migrations |
| `HealthEndpointTests` | HTTP integration, PostgreSQL | Health, liveness and readiness return `200` with `UP`; health hides component details; `env`, `configprops`, `beans` and `heapdump` are not exposed |

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
