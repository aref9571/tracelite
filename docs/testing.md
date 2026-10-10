# Testing

## Running tests

```bash
./mvnw test      # unit and integration tests
./mvnw verify    # tests plus packaging
```

## Continuous integration

`.github/workflows/ci.yml` runs on pushes to `main` and on pull requests. It runs `./mvnw -B -ntp verify` on JDK 21 and then starts the packaged jar to confirm the application boots. Docker-based integration tests and further quality checks will be added to the pipeline later.

## Current tests

| Test | Type | Verifies |
|---|---|---|
| `TraceLiteApplicationTests` | Spring Boot context | The application context starts with the default configuration |

## Strategy

As features are added, tests are split by what they verify:

- **Domain unit tests** — business rules such as fingerprinting and issue status transitions, without Spring.
- **Service tests** — application logic, using Mockito only where isolation is useful.
- **HTTP tests** — controller behaviour, validation, and error responses through MockMvc.
- **Persistence and integration tests** — repositories, constraints, transactions, and concurrent ingestion against real PostgreSQL via Testcontainers.
- **Security tests** — rejection of missing, malformed, and invalid credentials.

Tests use deterministic data and a controlled clock where time matters, and never rely on sleeps or execution order. JaCoCo coverage reporting is added on Day 9.
