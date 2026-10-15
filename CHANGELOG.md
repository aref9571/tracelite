# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- React and TypeScript dashboard in `frontend/` (Vite, React Router): an issue list with status and environment filters, pagination and totals, kept in the URL; an issue summary page; and loading, empty, filtered-empty, page-past-the-end, not-found and error states for network failures, rejected keys (`401`), forbidden keys (`403`), invalid parameters (`400`) and server errors.
- Dashboard API client with typed responses, request cancellation and retry; the management key is entered at runtime and kept in session storage; the backend URL is set with `VITE_API_BASE_URL`.
- Dashboard unit tests (Vitest) and ESLint configuration, and a CI job that lints, tests, type-checks and builds the dashboard.

- Backend container image (`backend/Dockerfile`): a multi-stage build with the Maven Wrapper, Spring Boot layers on `eclipse-temurin:21-jre-noble`, a non-root user, a readiness `HEALTHCHECK` and no secrets in any layer; `.dockerignore` limits the build context to the build files and main sources.
- `backend` service in `docker-compose.yml`: starts after PostgreSQL is healthy, reads `.env`, publishes on `127.0.0.1` (`TRACELITE_BACKEND_PORT`), stops gracefully and keeps the database in the named volume.
- `scripts/development/docker-stack-test.sh`: checks the health of both services, the non-root user, that no configured secret or `.env` file is in the image, ingestion and read-back through the container, graceful stop, and that data survives a restart of the stack.
- CI: a static-checks job (shellcheck, hadolint, actionlint) and a job that builds the image from a clean checkout and runs the Docker stack test; the `.env` generation moved to `scripts/ci/create-env-file.sh`.
- Maven Enforcer rules for dependency convergence and duplicate dependency declarations.
- JaCoCo coverage report on `./mvnw verify`, with a check that fails the build below 90% line or 85% branch coverage; CI publishes the coverage as a notice and uploads the HTML report.
- Tests for simultaneous events on a resolved issue, resolve requests during ingestion, byte-by-byte reads and character encodings in the request size limit, nested context values, and the warning logged when no API keys are configured.
- Testing documentation for coverage rules, uncovered code and transaction boundaries.
- API-key authentication with Spring Security ([ADR 0005](docs/decisions/0005-api-key-authentication.md)): per-application ingestion keys for `POST /api/v1/events`, which may only submit events for their own application, and a management key for every `/api/v1/issues` endpoint, both sent in the `X-API-Key` header and configured through `TRACELITE_SECURITY_*` environment variables.
- Keys are validated at startup, kept only as SHA-256 hashes and compared in constant time; missing, malformed and unknown keys get `401` with `WWW-Authenticate`, a key on the wrong endpoint or for another application gets `403`, both as problem details that never repeat the key. Without keys, every API call is rejected and a warning is logged.
- CORS for browser clients, limited to origins listed in `TRACELITE_SECURITY_ALLOWEDORIGINS` (none by default).
- The OpenAPI document declares the `X-API-Key` scheme, so Swagger UI can send a key.
- Security tests for every access rule, malformed and invalid keys, CORS, configuration validation and keys never appearing in logs; the smoke test and CI check the API keys against the packaged jar.
- Documentation of key generation, configuration, rotation and security limitations.
- `GET /api/v1/issues/{id}/events`: an issue's events with stack traces, timestamps, correlation ID and context, newest first by receive time with the event ID as tie-breaker, and page-based pagination.
- `POST /api/v1/issues/{id}/resolve` and `POST /api/v1/issues/{id}/reopen`, returning the updated issue. Repeated requests and invalid transitions get `409` with the current status and change nothing; a request that loses a race with another change gets `409 Concurrent modification` ([ADR 0004](docs/decisions/0004-issue-lifecycle-api.md)).
- `GET /api/v1/issues/{id}/stats`: occurrence count, stored event count, first and last seen, events in the last 24 hours and 7 days, and daily counts for the last 14 UTC days, read from one database snapshot.
- Service, controller and HTTP integration tests for event history, statistics and the issue lifecycle, including simultaneous resolve requests.
- `GET /api/v1/issues`: issue listing with filters for status, environment and a last-seen date range, case-insensitive text search over exception type and message, sorting by last seen, first seen or occurrence count, and page-based pagination with total counts. Sorting always ends with the issue ID, so the order is deterministic.
- `GET /api/v1/issues/{id}`: issue details, with `404` problem details for an unknown ID and `400` for an ID that is not a UUID.
- Validation of query and path parameters; values that cannot be converted are reported as `has an invalid value` without conversion details.
- Flyway migration `V2`: the `pg_trgm` extension and trigram indexes on `lower(exception_type)` and `lower(message)` for text search.
- OpenAPI 3 document at `/v3/api-docs` and Swagger UI at `/swagger-ui.html` (springdoc-openapi 2.8).
- Repository, service, controller and HTTP integration tests for issue queries.
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

- HTTP integration tests share one support class for setup, authenticated requests and simultaneous requests instead of repeating them in each class.
- All `/api/v1` endpoints now require an API key. Actuator endpoints other than `health` and `info`, which were already unexposed, now get `401` or `403` instead of `404`, and `OPTIONS /api/v1/events` needs an ingestion key.
- The backend uses Spring Data JPA instead of plain JDBC; Hibernate validates the entity mapping against the Flyway schema on startup and open-in-view is disabled.
