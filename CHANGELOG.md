# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Stack-frame fingerprinting: the exception type plus up to 30 normalised frames of the outermost exception (no messages, line numbers, module prefixes or runtime-generated numbers), with a fallback to the exception type when a trace has no Java frames.
- Issue creation and occurrence counting in one PostgreSQL upsert (`INSERT ... ON CONFLICT`), so simultaneous first events for a new fingerprint create one issue instead of one request failing with `500`.
- A new event for a resolved issue reopens it.
- Concurrency tests that send simultaneous events for a new and for an existing fingerprint.
- `POST /api/v1/events` for error ingestion: validated request and response DTOs, a transactional application service, `201 Created` with the event ID, issue ID and server receive time.
- Validation that matches the database limits, identifier rules for application, environment and correlation ID, a bounded `occurredAt` (5 minutes ahead to 30 days back), and rejection of null characters.
- A 256 KiB request-size limit for `/api/**`, enforced for both `Content-Length` and chunked bodies.
- Centralised exception handling with RFC 9457 problem details: `400`, `405`, `413`, `415`, `500` and `503`, with no internal details in responses.
- Configurable ingestion limits (`tracelite.ingestion.*`) and an injectable `Clock`.
- Controller, service, filter, repository and PostgreSQL integration tests, including malformed JSON, validation, size limits and transaction rollback.
- Flyway migration `V1` creating the `issues` and `events` tables, with a unique scoped fingerprint, check constraints for every core invariant, a cascading foreign key, and indexes for listing and event history.
- `Issue` and `ErrorEvent` JPA entities, with validated constructors, the `UNRESOLVED`/`RESOLVED` lifecycle and optimistic locking on issues.
- `IssueRepository` and `ErrorEventRepository` (Spring Data JPA).
- Domain unit tests and PostgreSQL repository and schema-constraint tests.
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

### Changed

- The backend uses Spring Data JPA instead of plain JDBC; Hibernate validates the entity mapping against the Flyway schema on startup and open-in-view is disabled.
