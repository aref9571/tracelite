# TraceLite

TraceLite is a lightweight application error-monitoring platform. Applications send structured error events to a REST API; TraceLite stores them in PostgreSQL, groups similar exceptions into issues using deterministic fingerprints, and gives developers APIs and a small web dashboard to investigate and resolve those issues.

> **Status: error ingestion, issue grouping, issue listing, event history, statistics, the resolve and reopen API and API-key authentication work; the dashboard is not built yet.** The backend accepts error events at `POST /api/v1/events`, validates them, and stores them in PostgreSQL attached to an issue. Events are grouped by a fingerprint of the exception type and normalised stack frames, and simultaneous events never create duplicate issues. Issues can be listed with filters, text search, sorting and pagination, and read by ID; their events can be paged newest first, their occurrence statistics read, and they can be resolved and reopened. Every API call needs an API key: applications send events with their own ingestion key, and the issue endpoints need the management key. See the status column below.

## The problem

When an application fails in production, developers need fast answers:

- What exception occurred, and where?
- Which application and environment produced it?
- How often is it happening, and when did it last happen?
- Which stack trace helps reproduce it?
- Has anyone acknowledged or resolved it?

Raw logs answer these questions slowly. TraceLite collects errors as structured events and groups repeated failures so they can be triaged as a single issue.

## Capabilities

| Capability | Status |
|---|---|
| Error event ingestion (`POST /api/v1/events`) with validation and request-size limits | Implemented |
| Deterministic fingerprinting and issue grouping ([ADR 0002](docs/decisions/0002-issue-fingerprinting.md)) | Implemented |
| Issue listing, filtering, search, sorting and pagination (`GET /api/v1/issues`), and issue details (`GET /api/v1/issues/{id}`) | Implemented |
| Paginated event history per issue (`GET /api/v1/issues/{id}/events`) | Implemented |
| Issue resolve / reopen lifecycle (`POST /api/v1/issues/{id}/resolve`, `/reopen`), with automatic reopen on a new event | Implemented |
| Issue occurrence statistics (`GET /api/v1/issues/{id}/stats`) | Implemented |
| API-key authentication: per-application ingestion keys and a management key, CORS limited to configured origins ([ADR 0005](docs/decisions/0005-api-key-authentication.md)) | Implemented |
| PostgreSQL schema for issues and events, with constraints and indexes | Implemented |
| Consistent RFC 9457 error responses without internal details | Implemented |
| Health, liveness and readiness endpoints | Implemented |
| OpenAPI documentation (`/v3/api-docs`, Swagger UI at `/swagger-ui.html`) | Implemented |
| React/TypeScript investigation dashboard | Planned |
| Docker Compose PostgreSQL for local development | Implemented |
| GitHub Actions CI: build, Testcontainers tests, Compose smoke test | Implemented |
| Backend container image and full CI quality checks | Planned |

## Technology stack

- **Backend:** Java 21, Spring Boot 3.5, Spring Security, Maven (with Maven Wrapper)
- **Persistence:** PostgreSQL 17 (with `pg_trgm` for text search), Flyway, Spring Data JPA (Hibernate)
- **API documentation:** springdoc-openapi (OpenAPI 3, Swagger UI)
- **Testing:** JUnit 5, AssertJ, Mockito, MockMvc, Spring Boot Test, Testcontainers, JaCoCo coverage reports
- **Infrastructure:** Docker Compose, GitHub Actions; backend Dockerfile planned
- **Frontend (planned):** React, TypeScript, Vite

## Repository structure

```
tracelite/
├── pom.xml                  Root Maven project (parent + aggregator)
├── mvnw, mvnw.cmd, .mvn/    Maven Wrapper
├── backend/                 Spring Boot application module
│   └── src/main/java/com/tracelite/
│       ├── config/          Application configuration
│       ├── security/        API authentication
│       ├── common/          Shared error handling and web support
│       ├── event/           Error event ingestion (api, application, domain, persistence)
│       └── issue/           Issue grouping and management (api, application, domain, persistence)
├── docker-compose.yml       Local PostgreSQL
├── frontend/                Dashboard (scaffolded later)
├── docs/                    Architecture, API, database, security, development, testing docs
│   └── decisions/           Architecture decision records
└── scripts/                 Development (smoke test) and CI helper scripts
```

Empty feature packages are tracked with `.gitkeep` files and will be populated as each feature is implemented.

## Getting started

Prerequisites: JDK 21 and Docker with Compose v2. Maven does not need to be installed; the wrapper downloads it.

```bash
cp .env.example .env                              # then set the password and API keys (below)
docker compose up --detach --wait postgres        # PostgreSQL 17 on 127.0.0.1:5432
./mvnw verify                                     # build and run tests (uses Testcontainers)
set -a; . ./.env; set +a                          # export the variables for the backend
./mvnw -pl backend spring-boot:run -Dspring-boot.run.profiles=local
curl http://localhost:8080/actuator/health/readiness
```

In `.env`, set `SPRING_DATASOURCE_PASSWORD`, a management key, and an ingestion key for an application called `demo`, each generated with `openssl rand -hex 32`:

```bash
TRACELITE_SECURITY_MANAGEMENTKEY=<output of openssl rand -hex 32>
TRACELITE_SECURITY_PROJECTS_0_APPLICATION=demo
TRACELITE_SECURITY_PROJECTS_0_INGESTIONKEY=<another output of openssl rand -hex 32>
```

Send a test event with the ingestion key (see [docs/api.md](docs/api.md) for every field and response):

```bash
curl -i http://localhost:8080/api/v1/events -H 'Content-Type: application/json' \
  -H "X-API-Key: $TRACELITE_SECURITY_PROJECTS_0_INGESTIONKEY" \
  -d '{"application":"demo","environment":"local","exceptionType":"java.lang.IllegalStateException","occurredAt":"'"$(date -u +%Y-%m-%dT%H:%M:%SZ)"'"}'
```

List the issues, newest first, and filter them, with the management key:

```bash
curl -H "X-API-Key: $TRACELITE_SECURITY_MANAGEMENTKEY" 'http://localhost:8080/api/v1/issues'
curl -H "X-API-Key: $TRACELITE_SECURITY_MANAGEMENTKEY" \
  'http://localhost:8080/api/v1/issues?status=UNRESOLVED&environment=local&query=illegalstate'
```

Investigate and resolve an issue, using an `id` from the list:

```bash
curl -H "X-API-Key: $TRACELITE_SECURITY_MANAGEMENTKEY" 'http://localhost:8080/api/v1/issues/<id>/events?size=5'
curl -H "X-API-Key: $TRACELITE_SECURITY_MANAGEMENTKEY" 'http://localhost:8080/api/v1/issues/<id>/stats'
curl -X POST -H "X-API-Key: $TRACELITE_SECURITY_MANAGEMENTKEY" 'http://localhost:8080/api/v1/issues/<id>/resolve'
```

Browse every endpoint in Swagger UI at <http://localhost:8080/swagger-ui.html>; use **Authorize** to enter a key.

On Windows use `mvnw.cmd` instead of `./mvnw`. [docs/development.md](docs/development.md) has the full setup, configuration reference and troubleshooting.

## Development milestones

| Day | Milestone |
|---|---|
| 1 | Repository structure and project foundation |
| 2 | Spring Boot configuration, PostgreSQL, Flyway, health checks |
| 3 | Domain model and database design |
| 4 | Error ingestion API |
| 5 | Fingerprinting and issue grouping |
| 6 | Issue listing and filtering |
| 7 | Event investigation and issue lifecycle |
| 8 | API authentication and security |
| 9 | Automated testing and code quality |
| 10 | Docker packaging and CI |
| 11 | Frontend foundation and issue dashboard |
| 12 | Issue details and resolution workflow |
| 13 | Performance, reliability, and engineering review |
| 14 | Release preparation and final verification |

## Documentation

- [Architecture](docs/architecture.md)
- [API](docs/api.md)
- [Database](docs/database.md)
- [Security](docs/security.md)
- [Development](docs/development.md)
- [Testing](docs/testing.md)
- [Architecture decision records](docs/decisions/)

## License

Released under the [MIT License](LICENSE).
