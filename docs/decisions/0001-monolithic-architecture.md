# ADR 0001: Modular monolithic architecture

- **Status:** Accepted
- **Date:** 2026-10-10

## Context

TraceLite ingests error events, groups them into issues, and serves investigation APIs and a dashboard. It is built and operated by a single developer at small scale. The core operations — ingesting an event and updating its issue — must be consistent with each other.

## Decision

Build TraceLite as a single Spring Boot application backed by a single PostgreSQL database. Organise the code by feature (`event`, `issue`) with layered packages inside each feature (`api`, `application`, `domain`, `persistence`). Build the React dashboard separately and serve it independently of the backend.

Do not introduce message brokers, caches, service discovery, or container orchestration unless a measured requirement justifies them.

## Consequences

- Event persistence and issue updates can share one database transaction, which keeps occurrence counts consistent without distributed coordination.
- Deployment is one container plus PostgreSQL.
- Feature packages keep boundaries visible, so a feature could be extracted later if needed.
- Ingestion and query traffic scale together; independent scaling is not possible without further work.
- A burst of ingestion traffic competes with dashboard queries for the same database. This is acceptable at the intended scale and will be revisited if benchmarks show otherwise.
