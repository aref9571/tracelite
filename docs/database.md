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

There are no migrations yet. The schema is designed and added on Day 3.

## Planned schema

- `issues`: one row per fingerprint group, with status, occurrence count, and first- and last-seen timestamps.
- `events`: one row per received error, referencing its issue.
- A uniqueness constraint on the scoped fingerprint prevents duplicate issues and is the basis for safe concurrent ingestion.

This section will be replaced by the actual schema, constraints, indexes and deletion policies once they exist.
