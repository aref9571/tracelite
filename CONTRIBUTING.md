# Contributing to TraceLite

## Prerequisites

- JDK 21
- Docker with Compose v2 (the integration tests use Testcontainers, and local PostgreSQL runs in Compose)
- Git

See [docs/development.md](docs/development.md) for the full local setup.

## Workflow

1. Create a branch from `main`.
2. Make a focused change.
3. Run `./mvnw verify` and make sure it passes.
4. Update the README and the relevant files in `docs/` when behaviour changes.
5. Open a pull request describing the change and how it was verified.

## Commit messages

Use [Conventional Commits](https://www.conventionalcommits.org/):

```
feat: implement error event ingestion
fix: handle concurrent issue creation
test: cover issue grouping edge cases
docs: document local development setup
build: configure Spring Boot and Maven
ci: add backend verification workflow
chore: initialize project structure
```

## Code standards

- Follow standard Java naming conventions and keep classes cohesive.
- Use constructor injection.
- Keep HTTP concerns in `api`, use cases and transactions in `application`, business rules in `domain`, and data access in `persistence`.
- Use explicit request and response DTOs and validate input at the API boundary.
- Prefer self-explanatory code over comments; record design decisions in `docs/decisions/`.
- Schema changes go through Flyway migrations. Never edit a migration that has been merged.
- Never commit secrets, `.env` files, or real credentials.

## Tests

- Unit-test domain rules in isolation.
- Test HTTP behaviour through MockMvc.
- Test PostgreSQL-dependent behaviour against a real database with Testcontainers.
- Do not disable or weaken tests to obtain a passing build.
