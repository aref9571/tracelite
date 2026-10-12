# ADR 0003: Event ingestion

- **Status:** Accepted
- **Date:** 2026-10-10, updated 2026-10-12

## Context

Client applications send error events to `POST /api/v1/events`. Each event must be validated, stored, and attached to an issue whose occurrence count and last-seen timestamp stay correct under concurrent requests.

## Decision

- **Synchronous ingestion.** The request is validated, then the event is stored and its issue created or updated in one database transaction owned by `EventIngestionService`. The API answers `201 Created` only after the commit; any failure rolls the whole transaction back.
- **Server-controlled values.** The server generates the event and issue IDs and takes `receivedAt` from its own clock.
- **Timestamps.** The client's `occurredAt` is required, stored on the event, and bounded: at most 5 minutes after the server's time (clock skew) and at most 30 days before it. Both bounds are configurable. Issue `first_seen_at` and `last_seen_at` use the server's `receivedAt`, so issue ordering and time filters never depend on client clocks.
- **Issue creation and counters in one statement.** `IssueRepository.recordOccurrence` runs `INSERT ... ON CONFLICT ON CONSTRAINT issues_scope_fingerprint_uk DO UPDATE ... RETURNING id`. A new scoped fingerprint inserts an issue with a count of 1; an existing one gets `occurrence_count + 1` and `GREATEST(last_seen_at, :seenAt)`. Nothing is read and written back in Java. When two first events for the same new fingerprint arrive together, PostgreSQL makes the second insert wait for the first transaction and then applies the update, so both requests succeed, one issue exists and its count is 2. This was chosen over catching the unique-constraint violation and retrying, because a failed insert aborts the PostgreSQL transaction and a retry would need a second transaction, which could commit the issue even if storing the event later failed.
- **Resolved issues reopen.** A new event for a `RESOLVED` issue sets it back to `UNRESOLVED` and clears `resolved_at` in the same statement, because a failure that happens again after a fix is a regression developers need to see. The statement increments `version` only when it reopens an issue, so counting events never conflicts with a concurrent resolve, but a resolve or reopen based on a read taken before an automatic reopen fails with an optimistic-locking error instead of silently overwriting it.
- **Sizes.** Request bodies are limited to 256 KiB (configurable). Field limits match the database columns: application 100, environment 50, exception type 255, message 4,000, stack trace 65,536 and correlation ID 128 characters; `context` is a JSON object with at most 50 top-level entries. 256 KiB leaves room for a maximum-length stack trace with JSON escaping plus the other fields.
- **Errors.** Invalid or malformed payloads get `400`, oversized ones `413`, unsupported media types `415`, an unreachable database `503`, and other failures `500`, all as RFC 9457 problem details with generic messages.
- **Null characters** in any text field or inside `context` are rejected with `400`, because PostgreSQL `text`, `varchar` and `jsonb` cannot store them and the insert would otherwise fail with `500`.

## Consequences

Synchronous ingestion keeps the design simple and makes failures visible to clients, at the cost of tying ingestion latency to database latency. Asynchronous ingestion is out of scope unless measurements justify it. Rejecting very old or future-dated events protects issue timelines from wrong client clocks, but a client that buffers events offline for more than 30 days loses them unless the limit is raised. The upsert is PostgreSQL-specific SQL; this is acceptable because PostgreSQL is the only supported database. Reopening uses the server's `receivedAt`, so an event that a client delivers late, after the issue was resolved, also reopens it, even if it occurred before the fix.
