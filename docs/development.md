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

   Then add API keys, so you can call the API (see [security.md](security.md#api-keys)):

   ```bash
   sed -i.bak "s/^TRACELITE_SECURITY_MANAGEMENTKEY=.*/TRACELITE_SECURITY_MANAGEMENTKEY=$(openssl rand -hex 32)/" .env && rm .env.bak
   printf 'TRACELITE_SECURITY_PROJECTS_0_APPLICATION=demo\nTRACELITE_SECURITY_PROJECTS_0_INGESTIONKEY=%s\n' "$(openssl rand -hex 32)" >> .env
   ```

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

There are two ways to run the backend: as a container with Docker Compose, or from Maven or the packaged jar on your machine. Both use the same `.env`.

### With Docker Compose

```bash
docker compose up --detach --wait
```

This builds the backend image from `backend/Dockerfile` on first use, starts PostgreSQL, waits until it is healthy, then starts the backend and waits until its readiness check passes. The backend is published on `127.0.0.1:8080` (`TRACELITE_BACKEND_PORT` changes the host port). Rebuild the image after changing the code with `docker compose up --detach --wait --build`.

| Command | Effect |
|---|---|
| `docker compose ps` | Shows both services and their health |
| `docker compose logs --follow backend` | Follows the backend log |
| `docker compose stop backend` | Stops the backend gracefully (it gets 30 seconds before it is killed) |
| `docker compose down` | Removes the containers; the database volume and its data are kept |
| `docker compose down --volumes` | Removes the containers and deletes the database data |

The backend container reads every variable in `.env` (`env_file`), except that Compose sets `SPRING_DATASOURCE_URL` to the `postgres` service and `SERVER_PORT` to `8080`, because inside the Compose network the database is not on `localhost`. To run only the database and start the backend from your IDE or Maven instead, use `docker compose up --detach --wait postgres`. Do not run both the container and a local backend on the same host port.

The image:

- is built in two stages: a JDK stage runs `./mvnw package -DskipTests` (the tests run in CI, not in the image build) and extracts the jar into Spring Boot's layers, and the runtime stage is `eclipse-temurin:21-jre-noble` with only those layers, so a code change rebuilds only the small application layer;
- runs as the system user `tracelite`, not root, and the application files belong to root, so the process cannot modify them;
- contains no configuration secrets: `.dockerignore` sends only the Maven Wrapper, the two `pom.xml` files and `backend/src/main` to the build, so `.env` can never be copied in, and every password and key is supplied when the container starts;
- has a `HEALTHCHECK` on `/actuator/health/readiness`, which Compose uses for `--wait` and `docker compose ps`;
- limits the JVM heap to 75% of the container's memory limit (`-XX:MaxRAMPercentage=75`).

To build the image on its own: `docker build -f backend/Dockerfile -t tracelite-backend .` from the repository root.

### With Maven or the jar

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

On startup Flyway applies any pending migrations from `backend/src/main/resources/db/migration`. Migration `V1` creates the `issues` and `events` tables and `V2` adds the issue search indexes; see [database.md](database.md).

With the backend running, the OpenAPI document is at <http://localhost:8080/v3/api-docs> and Swagger UI at <http://localhost:8080/swagger-ui.html>. Both are public; to try a protected endpoint in Swagger UI, choose **Authorize** and enter a key. Without any keys configured the backend starts, logs `No API keys are configured, so every request to /api/** will be rejected`, and answers every API call with `401`.

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
| `SERVER_PORT` | `8080` | no | Backend HTTP port when run with Maven or the jar; the container always uses `8080` |
| `TRACELITE_BACKEND_PORT` | `8080` | no | Host port Compose publishes the backend container on |
| `TRACELITE_INGESTION_MAX_REQUEST_SIZE` | `256KB` | no | Largest accepted body for `/api/**` requests; larger bodies get `413` |
| `TRACELITE_INGESTION_MAX_CLOCK_SKEW` | `5m` | no | How far after the server's time an event's `occurredAt` may be |
| `TRACELITE_INGESTION_MAX_EVENT_AGE` | `30d` | no | How far before the server's time an event's `occurredAt` may be |
| `SPRINGDOC_API_DOCS_ENABLED` | `true` | no | Serve the OpenAPI document at `/v3/api-docs` |
| `SPRINGDOC_SWAGGER_UI_ENABLED` | `true` | no | Serve Swagger UI at `/swagger-ui.html` (needs the OpenAPI document) |
| `TRACELITE_SECURITY_MANAGEMENTKEY` | — | for the issue endpoints | Management API key for every `/api/v1/issues/**` endpoint. Empty means no management access |
| `TRACELITE_SECURITY_PROJECTS_<n>_APPLICATION` | — | for ingestion | Application name the `n`-th ingestion key belongs to (`n` = 0, 1, 2, ...); an application may appear more than once to allow key rotation |
| `TRACELITE_SECURITY_PROJECTS_<n>_INGESTIONKEY` | — | for ingestion | Ingestion key for that application; required whenever the application is set |
| `TRACELITE_SECURITY_ALLOWEDORIGINS` | none | no | Comma-separated browser origins allowed to call the API, e.g. `http://localhost:5173` |

The `SPRING_DATASOURCE_*` names are Spring Boot's standard environment-variable mapping for `spring.datasource.*`, so no custom placeholders are involved. The `TRACELITE_INGESTION_*` and `TRACELITE_SECURITY_*` variables map to `tracelite.ingestion.*` and `tracelite.security.*` the same way (Spring drops the dashes, so `management-key` becomes `MANAGEMENTKEY`, and `projects[0].ingestion-key` becomes `PROJECTS_0_INGESTIONKEY`); sizes use Spring's `DataSize` format (`256KB`) and durations its `Duration` format (`5m`, `30d`).

### Missing configuration

The backend refuses to start without database settings. Starting it with no `SPRING_DATASOURCE_URL` fails with:

```
Failed to configure a DataSource: 'url' attribute is not specified and no embedded datasource could be configured.
```

A malformed API key, such as one that is too short or contains spaces, also stops startup, with a message that names the property but not its value, for example `tracelite.security.management-key must be 32 to 128 letters, digits, '_' or '-'`. Two equal keys stop startup too.

A wrong password fails at startup with PostgreSQL's `password authentication failed` error, because Flyway connects to the database before the application accepts traffic. Docker Compose refuses to start PostgreSQL without a password:

```
required variable SPRING_DATASOURCE_PASSWORD is missing a value: Set SPRING_DATASOURCE_PASSWORD in .env
```

## Profiles

| Profile | Use | Differences |
|---|---|---|
| default | Any deployed environment | Minimal health output, `INFO` logging |
| `local` | Local development | Health component details, `DEBUG` logging for `com.tracelite` |

Tests do not use a dedicated profile. Testcontainers provides the database connection through Spring Boot's `@ServiceConnection`, and `backend/src/test/resources/config/application.yml` adds fixed test-only API keys, which Spring Boot loads in addition to the main `application.yml`. Those keys exist only in the test classpath and are never valid anywhere else.

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

It needs the management key and the first ingestion key (`TRACELITE_SECURITY_PROJECTS_0_*`) in `.env`. Stop the backend container first (`docker compose stop backend`) if it is running, because the script starts the jar on the same port. It verifies that startup without database configuration, or with a wrong password, fails with the expected message, that the health, liveness and readiness endpoints report `UP`, that the API answers `401` without a key or with a wrong key, `403` when a key is used on the wrong endpoint, and `201` and `200` with the right keys, and that `SIGTERM` produces a graceful shutdown. The ingestion check stores one event with the environment `smoke-test`. CI runs the same script with freshly generated keys.

## Docker stack test

`scripts/development/docker-stack-test.sh` checks the image and the full Compose stack:

```bash
set -a; . ./.env; set +a
scripts/development/docker-stack-test.sh
```

It starts both services and waits until they are healthy, then checks that the backend container does not run as root, that none of the configured secrets from `.env` appears in the image metadata or build history and that the image contains no `.env` file, that an event can be ingested and its issue read back, that `docker compose stop` produces a graceful shutdown, and that the issue is still there after `docker compose down` and a fresh start, which proves the data lives in the volume. It stores one event with the environment `docker-stack-test` and leaves the stack running. CI runs the same script with freshly generated keys.
