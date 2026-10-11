# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Flyway migration `V1` creating the `issues` and `events` tables, with a unique scoped fingerprint, check constraints for every core invariant, a cascading foreign key, and indexes for listing and event history.
- `Issue` and `ErrorEvent` JPA entities, with validated constructors, the `UNRESOLVED`/`RESOLVED` lifecycle and optimistic locking on issues.
- `IssueRepository` and `ErrorEventRepository` (Spring Data JPA).
- Domain unit tests and PostgreSQL repository and schema-constraint tests.

### Changed

- The backend uses Spring Data JPA instead of plain JDBC; Hibernate validates the entity mapping against the Flyway schema on startup and open-in-view is disabled.

- PostgreSQL 17 for local development through Docker Compose, bound to localhost, with a health check and a persistent volume.
- Database configuration through `SPRING_DATASOURCE_*` environment variables, with no default credentials.
- Flyway configured with validation on migrate and `clean` disabled.
- Actuator health, liveness and readiness endpoints; readiness includes the database. Only `health` and `info` are exposed.
- Graceful shutdown, log levels, and a `local` profile for development.
- Testcontainers integration tests for application startup, database connectivity, Flyway configuration and health endpoints.
- `scripts/development/smoke-test.sh`, which checks startup failure on missing or wrong configuration, health probes and graceful shutdown.
- CI runs on every branch and pull request, and runs the smoke test against the Docker Compose database.
- CI publishes test totals, Maven errors and failing test reports as check-run annotations.
- Repository structure, root and backend Maven configuration, and Maven Wrapper.
- Minimal Spring Boot application and application-context test.
- GitHub Actions workflow that builds the backend, runs the tests, and checks that the packaged application starts.
- Initial README, contribution guide, security policy, and technical documentation.
- Architecture decision records for the monolithic architecture, issue fingerprinting, and event ingestion.
