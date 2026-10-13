# ADR 0004: Issue lifecycle API

- **Status:** Accepted
- **Date:** 2026-10-13

## Context

Developers resolve an issue when they believe it is fixed and reopen it when it is not. The domain already allows only two transitions, `UNRESOLVED` to `RESOLVED` and back, and ingestion reopens a resolved issue when a new event arrives ([ADR 0003](0003-event-ingestion.md)). The API has to expose these operations, behave predictably when a client repeats a request, and stay correct when requests and events race.

## Decision

- **Two action endpoints.** `POST /api/v1/issues/{id}/resolve` and `POST /api/v1/issues/{id}/reopen`, without a request body, returning the updated issue with `200`. Separate actions were chosen over `PATCH` with a `status` field because each action has its own rule and side effect (`resolvedAt`), and the two-state lifecycle has nothing else to patch.
- **Repeated requests get `409 Conflict`.** Resolving a resolved issue or reopening an unresolved one is an invalid transition: nothing changes, the original `resolvedAt` is kept, and the response is a `409` problem detail with `currentStatus` and `requestedStatus`. This was chosen over silently returning `200`, because a silent success would hide a real conflict, for example two developers acting on the same issue, while the `currentStatus` field still lets a retrying client see that the issue is already in the state it wanted.
- **Optimistic locking for races.** The service saves with `saveAndFlush`, so the `@Version` check runs inside the transaction. When another resolve or reopen, or an automatic reopen by ingestion, commits first, the losing request fails with `409 Concurrent modification` and changes nothing. The client can fetch the issue and decide again.
- **Server time.** `resolvedAt` comes from the server clock, never from the client.

## Consequences

Clients must treat `409` as "already in that state or changed by someone else" and read `currentStatus` or fetch the issue, rather than retrying blindly. Recording an event for an unresolved issue does not change `version` (ADR 0003), so a resolve that races with ordinary ingestion succeeds; an event received in the same moment may be counted just before the issue is resolved. There is no audit history of status changes yet; only the current status and the latest `resolvedAt` are stored. Day 8 adds authentication to these endpoints.
