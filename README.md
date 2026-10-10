# TraceLite

TraceLite is a lightweight application error-monitoring platform. Applications send structured error events to a REST API; TraceLite stores them in PostgreSQL, groups similar exceptions into issues using deterministic fingerprints, and gives developers APIs and a small web dashboard to investigate and resolve those issues.

> **Status: initial skeleton.** The repository currently contains the project structure, build configuration, documentation, and a minimal Spring Boot application with no business functionality. None of the capabilities listed under *Planned capabilities* are implemented yet.

## The problem

When an application fails in production, developers need fast answers:

- What exception occurred, and where?
- Which application and environment produced it?
- How often is it happening, and when did it last happen?
- Which stack trace helps reproduce it?
- Has anyone acknowledged or resolved it?

Raw logs answer these questions slowly. TraceLite collects errors as structured events and groups repeated failures so they can be triaged as a single issue.

## Planned capabilities

| Capability | Status |
|---|---|
| Error event ingestion (`POST /api/v1/events`) with validation | Planned |
| Deterministic fingerprinting and issue grouping | Planned |
| Issue listing, filtering, search, and pagination | Planned |
| Paginated event history per issue | Planned |
| Issue resolve / reopen lifecycle | Planned |
| Issue occurrence statistics | Planned |
| API-key authentication for ingestion and management | Planned |
| Health and readiness endpoints | Planned |
| OpenAPI documentation | Planned |
| React/TypeScript investigation dashboard | Planned |
| GitHub Actions CI: backend build, tests, and startup check | Implemented (basic) |
| Docker Compose local environment and full CI pipeline | Planned |

## Technology stack

- **Backend:** Java 21, Spring Boot 3.5, Maven (with Maven Wrapper)
- **Persistence (planned):** PostgreSQL, Spring Data JPA, Flyway
- **Testing:** JUnit 5, Spring Boot Test; Mockito, MockMvc, Testcontainers and JaCoCo as features arrive
- **Infrastructure (planned):** Docker, Docker Compose, GitHub Actions
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
├── frontend/                Dashboard (scaffolded later)
├── docs/                    Architecture, API, database, security, development, testing docs
│   └── decisions/           Architecture decision records
└── scripts/development/     Local development helper scripts
```

Empty feature packages are tracked with `.gitkeep` files and will be populated as each feature is implemented.

## Getting started

Prerequisites: JDK 21. Maven does not need to be installed; the wrapper downloads it.

```bash
./mvnw verify                                   # build and run tests
./mvnw -pl backend spring-boot:run              # start the application on port 8080
```

On Windows use `mvnw.cmd` instead of `./mvnw`. See [docs/development.md](docs/development.md) for details.

The application currently starts with no API endpoints of its own.

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
