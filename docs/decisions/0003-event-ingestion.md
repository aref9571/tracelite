# ADR 0003: Event ingestion

- **Status:** Proposed
- **Date:** 2026-10-10

## Context

Client applications send error events to `POST /api/v1/events`. Each event must be validated, stored, and attached to an issue whose occurrence count and last-seen timestamp stay correct under concurrent requests.

## Proposed direction

- Ingest synchronously: validate the request, then persist the event and update its issue in one database transaction in the application service.
- Generate identifiers and the received-at timestamp on the server.
- Rely on a database uniqueness constraint on the scoped fingerprint to prevent duplicate issues when two requests for a new fingerprint arrive at once, and update counters with atomic SQL rather than read-modify-write in Java.
- Reject invalid or oversized payloads with `400`/`413` responses in a consistent error format; report persistence failures as `5xx`, never as success.

## Open questions

- Upsert strategy for issue creation (`INSERT ... ON CONFLICT` versus catching constraint violations and retrying).
- Maximum payload and field sizes.
- Whether the client-supplied occurrence timestamp is trusted, bounded, or only stored alongside the server timestamp.

## Consequences

Synchronous ingestion keeps the design simple and makes failures visible to clients, at the cost of tying ingestion latency to database latency. Asynchronous ingestion is out of scope unless measurements justify it. This record will be finalised when ingestion and grouping are implemented.
