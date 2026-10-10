# Development

## Prerequisites

| Tool | Version | Needed for |
|---|---|---|
| JDK | 21 | Building and running the backend |
| Docker | recent, with Compose v2 | Local PostgreSQL and the integration tests |
| Git | any recent | Source control |
| Node.js | 22 LTS | Dashboard (from Day 11) |

Maven is not required: the Maven Wrapper (`./mvnw`) downloads Maven 3.9.11 on first use. Run all commands from the repository root. On Windows, use `mvnw.cmd` in place of `./mvnw`.

## First-time setup

1. Create your local environment file and give it a password:

   ```bash
   cp .env.example .env
   sed -i.bak "s/^SPRING_DATASOURCE_PASSWORD=.*/SPRING_DATASOURCE_PASSWORD=$(openssl rand -hex 24)/" .env && rm .env.bak
   ```

   Or edit `.env` by hand. `.env` is git-ignored; never commit it.

2. Start PostgreSQL:

   ```bash
   docker compose up --detach --wait postgres
   ```

   PostgreSQL 17 listens on `127.0.0.1:5432` only. Data lives in the `tracelite_postgres-data` Docker volume and survives restarts.

3. Build and test:

   ```bash
   ./mvnw verify
   ```

   The tests start their own throwaway PostgreSQL containers with Testcontainers, so Docker must be running. They do not use the Compose database.

## Running the backend

Spring Boot does not read `.env`, so export its variables into your shell first:

```bash
set -a; . ./.env; set +a
./mvnw -pl backend spring-boot:run -Dspring-boot.run.profiles=local
```

or run the packaged jar:

```bash
set -a; . ./.env; set +a
java -jar backend/target/tracelite-backend.jar --spring.profiles.active=local
```

On startup Flyway applies any pending migrations from `backend/src/main/resources/db/migration`. There are no migrations yet; the schema arrives on Day 3.

Stop the application with `Ctrl+C`. It shuts down gracefully: in-flight requests get up to 20 seconds to complete.

Stop PostgreSQL with `docker compose down`. Add `--volumes` to delete the data as well.

## Health checks

| Endpoint | Meaning |
|---|---|
| `GET /actuator/health` | Overall status |
| `GET /actuator/health/liveness` | The process is running and not broken |
| `GET /actuator/health/readiness` | Ready for traffic, including a working database connection |

```bash
curl http://localhost:8080/actuator/health/readiness
# {"status":"UP"}
```

Readiness returns HTTP 503 when the database is unreachable. In the default profile only the status is shown; the `local` profile also shows component details. `/actuator/info` is the only other exposed actuator endpoint.

## Configuration

| Variable | Default | Required | Description |
|---|---|---|---|
| `SPRING_DATASOURCE_URL` | — | yes | JDBC URL, e.g. `jdbc:postgresql://localhost:5432/tracelite` |
| `SPRING_DATASOURCE_USERNAME` | — (`tracelite` in Compose) | yes | Database user |
| `SPRING_DATASOURCE_PASSWORD` | — | yes | Database password; also used by Compose to initialise PostgreSQL |
| `TRACELITE_DB_PORT` | `5432` | no | Host port Compose publishes PostgreSQL on |
| `SERVER_PORT` | `8080` | no | Backend HTTP port |

The `SPRING_DATASOURCE_*` names are Spring Boot's standard environment-variable mapping for `spring.datasource.*`, so no custom placeholders are involved.

### Missing configuration

The backend refuses to start without database settings. Starting it with no `SPRING_DATASOURCE_URL` fails with:

```
Failed to configure a DataSource: 'url' attribute is not specified and no embedded datasource could be configured.
```

A wrong password fails at startup with PostgreSQL's `password authentication failed` error, because Flyway connects to the database before the application accepts traffic. Docker Compose refuses to start PostgreSQL without a password:

```
required variable SPRING_DATASOURCE_PASSWORD is missing a value: Set SPRING_DATASOURCE_PASSWORD in .env
```

## Profiles

| Profile | Use | Differences |
|---|---|---|
| default | Any deployed environment | Minimal health output, `INFO` logging |
| `local` | Local development | Health component details, `DEBUG` logging for `com.tracelite` |

Tests do not use a dedicated profile. Testcontainers provides the database connection through Spring Boot's `@ServiceConnection`.

## Logging

Logs go to the console in Spring Boot's default format. Levels are set in `application.yml` and can be overridden per package with environment variables, e.g. `LOGGING_LEVEL_ORG_FLYWAYDB=DEBUG`. Structured logs and correlation identifiers are planned for a later milestone.

## Smoke test

`scripts/development/smoke-test.sh` checks a built jar end to end against a running PostgreSQL:

```bash
./mvnw verify
docker compose up --detach --wait postgres
set -a; . ./.env; set +a
scripts/development/smoke-test.sh
```

It verifies that startup without database configuration, or with a wrong password, fails with the expected message, that the health, liveness and readiness endpoints report `UP`, and that `SIGTERM` produces a graceful shutdown. CI runs the same script.
