# ADR 0003: Event ingestion

- **Status:** Accepted (issue-creation concurrency to be completed on Day 5)
- **Date:** 2026-10-10, updated 2026-10-12

## Context

Client applications send error events to `POST /api/v1/events`. Each event must be validated, stored, and attached to an issue whose occurrence count and last-seen timestamp stay correct under concurrent requests.

## Decision

- **Synchronous ingestion.** The request is validated, then the event is stored and its issue created or updated in one database transaction owned by `EventIngestionService`. The API answers `201 Created` only after the commit; any failure rolls the whole transaction back.
- **Server-controlled values.** The server generates the event and issue IDs and takes `receivedAt` from its own clock.
- **Timestamps.** The client's `occurredAt` is required, stored on the event, and bounded: at most 5 minutes after the server's time (clock skew) and at most 30 days before it. Both bounds are configurable. Issue `first_seen_at` and `last_seen_at` use the server's `receivedAt`, so issue ordering and time filters never depend on client clocks.
- **Counters.** For an existing issue, `occurrence_count` and `last_seen_at` are updated by one SQL statement (`occurrence_count + 1`, `GREATEST(last_seen_at, :seenAt)`), never by read-modify-write in Java.
- **Sizes.** Request bodies are limited to 256 KiB (configurable). Field limits match the database columns: application 100, environment 50, exception type 255, message 4,000, stack trace 65,536 and correlation ID 128 characters; `context` is a JSON object with at most 50 top-level entries. 256 KiB leaves room for a maximum-length stack trace with JSON escaping plus the other fields.
- **Errors.** Invalid or malformed payloads get `400`, oversized ones `413`, unsupported media types `415`, an unreachable database `503`, and other failures `500`, all as RFC 9457 problem details with generic messages.
- **Null characters** in any text field or inside `context` are rejected with `400`, because PostgreSQL `text`, `varchar` and `jsonb` cannot store them and the insert would otherwise fail with `500`.

## Still open

- **Upsert strategy for issue creation.** Day 4 looks the issue up and inserts it if missing. Two first events for the same new scoped fingerprint arriving at the same moment can still make one request fail on the unique constraint (with `500`, never a duplicate issue). Day 5 chooses between `INSERT ... ON CONFLICT` and catching the violation and retrying, and adds concurrency tests.

## Consequences

Synchronous ingestion keeps the design simple and makes failures visible to clients, at the cost of tying ingestion latency to database latency. Asynchronous ingestion is out of scope unless measurements justify it. Rejecting very old or future-dated events protects issue timelines from wrong client clocks, but a client that buffers events offline for more than 30 days loses them unless the limit is raised.
