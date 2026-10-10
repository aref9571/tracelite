# Architecture

## Overview

TraceLite is a modular monolith: one Spring Boot application, one PostgreSQL database, and a separately built React dashboard. See [ADR 0001](decisions/0001-monolithic-architecture.md) for the reasoning.

```
 Client applications ──► POST /api/v1/events ─┐
                                              ▼
                                 ┌─────────────────────────┐
 Dashboard (React) ─► /api/v1 ─► │  TraceLite backend      │ ──► PostgreSQL
                                 │  (Spring Boot)          │
                                 └─────────────────────────┘
```

Only the empty Spring Boot application exists today. The diagram describes the target design.

## Build layout

The repository uses a Maven multi-module layout:

- `pom.xml` (root, `tracelite-parent`) inherits `spring-boot-starter-parent`, declares shared properties (Java 21, encoding), enforces the minimum Java and Maven versions, and aggregates the modules.
- `backend/pom.xml` (`tracelite-backend`) is the only Java module and contains the Spring Boot application.
- `frontend/` is built with npm and Vite, not Maven.

The Maven Wrapper lives at the root, so every build command runs from the repository root. The multi-module root keeps backend build configuration separate from repository-level files and gives one place for shared build rules, without introducing extra Java modules.

Spring Boot is pinned to 3.5.16, the latest 3.x release line, as required by the project specification. Open-source support for Spring Boot 3.5 ended in June 2026; migrating to Spring Boot 4 is a future consideration documented here rather than taken on during the initial release.

## Package structure

Code under `backend/src/main/java/com/tracelite` is organised by feature, with layers inside each feature:

| Package | Responsibility |
|---|---|
| `config` | Spring configuration and typed properties |
| `security` | API-key authentication |
| `common.exception` | Shared exception types and centralised error handling |
| `common.web` | Shared web concerns such as pagination and error response DTOs |
| `event.api` / `issue.api` | Controllers and request/response DTOs |
| `event.application` / `issue.application` | Use cases and transaction boundaries |
| `event.domain` / `issue.domain` | Entities, value objects, and business rules |
| `event.persistence` / `issue.persistence` | Spring Data repositories |

### Layering rules

- Controllers translate HTTP to application calls; they contain no queries or business rules.
- Application services own transactions and orchestrate domain objects and repositories.
- Domain code does not depend on web or persistence frameworks beyond JPA mapping.
- Repositories never decide HTTP status codes.
- Interfaces are introduced only where they add a real boundary or testing benefit.

## Configuration

Configuration comes from `application.yml` with environment-variable overrides. Secrets are never stored in the repository. Variables are documented in `.env.example` and [development.md](development.md).

## Error handling

Spring Boot's default error responses are configured never to include stack traces, exception class names, or exception messages. A centralised exception handler with a consistent error format will be added with the first API endpoint.
