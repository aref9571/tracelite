# API

All endpoints are versioned under `/api/v1`. Requests and responses use JSON; errors use [RFC 9457 problem details](https://www.rfc-editor.org/rfc/rfc9457) (`application/problem+json`).

Authentication is not implemented yet. API-key authentication for these endpoints is planned for Day 8.

## Endpoints

| Method | Path | Purpose | Status |
|---|---|---|---|
| `POST` | `/api/v1/events` | Ingest an error event | Implemented |
| `GET` | `/api/v1/issues` | List, filter, and search issues | Implemented |
| `GET` | `/api/v1/issues/{id}` | Retrieve issue details | Implemented |
| `GET` | `/api/v1/issues/{id}/events` | Retrieve paginated events for an issue | Implemented |
| `POST` | `/api/v1/issues/{id}/resolve` | Mark an issue as resolved | Implemented |
| `POST` | `/api/v1/issues/{id}/reopen` | Reopen a resolved issue | Implemented |
| `GET` | `/api/v1/issues/{id}/stats` | Retrieve occurrence statistics | Implemented |

## OpenAPI

The running backend publishes an OpenAPI 3 description of the implemented endpoints, generated from the code by springdoc-openapi:

- `GET /v3/api-docs`: the OpenAPI document as JSON.
- `GET /swagger-ui.html`: Swagger UI for browsing and trying the endpoints.

Like the rest of the API, these are not authenticated yet; Day 8 decides how they are exposed.

## `POST /api/v1/events`

Stores one error event and attaches it to an issue. The event and the issue update are written in one database transaction, so a `201` response means both are committed.

### Request

`Content-Type: application/json`

```json
{
  "application": "checkout-service",
  "environment": "production",
  "exceptionType": "java.lang.IllegalStateException",
  "message": "Cart is empty",
  "stackTrace": "java.lang.IllegalStateException: Cart is empty\n\tat com.example.cart.CartService.checkout(CartService.java:42)\n\tat com.example.cart.CartController.checkout(CartController.java:27)",
  "occurredAt": "2026-10-12T08:59:30Z",
  "correlationId": "req-7f3a9c",
  "context": {
    "orderId": "o-1001",
    "retry": 2
  }
}
```

| Field | Type | Required | Rules |
|---|---|---|---|
| `application` | string | Yes | 1 to 100 characters: letters, digits, `.`, `_`, `:` or `-` |
| `environment` | string | Yes | 1 to 50 characters: letters, digits, `.`, `_`, `:` or `-` |
| `exceptionType` | string | Yes | Not blank, at most 255 characters |
| `message` | string | No | At most 4,000 characters |
| `stackTrace` | string | No | At most 65,536 characters |
| `occurredAt` | ISO 8601 timestamp with an offset | Yes | No more than 5 minutes after the server's time and no more than 30 days before it |
| `correlationId` | string | No | 1 to 128 characters: letters, digits, `.`, `_`, `:` or `-` |
| `context` | JSON object | No | At most 50 top-level entries; values can be any JSON |

Further rules:

- The whole request body must not exceed 256 KiB (262,144 bytes).
- No text field, and no key or string value inside `context`, may contain the null character (`\u0000`), because PostgreSQL cannot store it.
- Unknown fields are ignored, so clients can send fields that a later version may support.
- The server sets the event ID, the issue ID and `receivedAt`. Clients cannot supply them.

The limits on `occurredAt` and the request size are configurable (see [development.md](development.md#configuration)).

### Grouping

The server computes a fingerprint from `exceptionType` and the Java stack frames in `stackTrace`, and finds the issue with the same application, environment and fingerprint:

- If none exists, it creates an `UNRESOLVED` issue with an occurrence count of 1, first-seen and last-seen times set to `receivedAt`, and the event's message.
- If one exists, it adds 1 to the occurrence count and moves last-seen to `receivedAt` (never backwards). A `RESOLVED` issue becomes `UNRESOLVED` again.

The fingerprint ignores the message, line numbers, file names, module prefixes and numbers in runtime-generated class names, so the same failure stays in one issue across deploys. Events without a stack trace, or whose trace has no lines of the form `at class.method(...)`, are grouped by exception type alone. The full algorithm, examples and limitations are in [ADR 0002](decisions/0002-issue-fingerprinting.md).

Simultaneous events for the same new failure all get `201` and join one issue ([ADR 0003](decisions/0003-event-ingestion.md)).

### Responses

**`201 Created`**: the event was stored.

```json
{
  "eventId": "9d2c4e6a-1b3f-4a5c-8e7d-2f4a6b8c0d1e",
  "issueId": "5b1f7c9e-3f4a-4d2b-9a61-0c8e2f3d4a5b",
  "receivedAt": "2026-10-12T09:00:00.123456Z"
}
```

**`400 Bad Request`**, invalid fields. Field errors are sorted by field name. Rejected values are not echoed back.

```json
{
  "type": "about:blank",
  "title": "Invalid request",
  "status": 400,
  "detail": "The request contains invalid fields.",
  "instance": "/api/v1/events",
  "errors": [
    { "field": "application", "message": "must not be null" },
    { "field": "occurredAt", "message": "must not be null" }
  ]
}
```

A timestamp outside the allowed range is reported the same way, for example `{ "field": "occurredAt", "message": "must not be after 2026-10-12T09:05:00.123456Z" }`.

**`400 Bad Request`**, malformed body: the body is empty, is not valid JSON, is not a JSON object, or has a field of the wrong type (for example, an unparseable `occurredAt` or a `context` that is not an object).

```json
{
  "type": "about:blank",
  "title": "Malformed request",
  "status": 400,
  "detail": "The request body is missing, is not valid JSON, or has a field of the wrong type.",
  "instance": "/api/v1/events"
}
```

Other responses, all with a problem-details body:

| Status | When |
|---|---|
| `405 Method Not Allowed` | Any method other than `POST` or `OPTIONS` (`OPTIONS` returns `200` with an `Allow: POST,OPTIONS` header) |
| `413 Payload Too Large` | The body is larger than 256 KiB. A declared `Content-Length` is checked before reading; a chunked body is rejected as soon as the limit is passed |
| `415 Unsupported Media Type` | `Content-Type` is not `application/json` |
| `500 Internal Server Error` | An unexpected failure, including a database error. Nothing is stored. The body says only "An unexpected error occurred." |
| `503 Service Unavailable` | The database cannot be reached or a transient database error occurred. Nothing is stored; the client can retry |

### Example

```bash
curl -i http://localhost:8080/api/v1/events \
  -H 'Content-Type: application/json' \
  -d '{
    "application": "checkout-service",
    "environment": "local",
    "exceptionType": "java.lang.IllegalStateException",
    "message": "Cart is empty",
    "occurredAt": "'"$(date -u +%Y-%m-%dT%H:%M:%SZ)"'"
  }'
```

## `GET /api/v1/issues`

Lists issues, one page at a time. Every parameter is optional.

| Parameter | Type | Default | Rules and meaning |
|---|---|---|---|
| `status` | `UNRESOLVED` or `RESOLVED` | any status | Exact match |
| `environment` | string | any environment | Exact, case-sensitive match. 1 to 50 characters: letters, digits, `.`, `_`, `:` or `-` |
| `lastSeenFrom` | ISO 8601 timestamp | none | Issues last seen at or after this time (inclusive) |
| `lastSeenTo` | ISO 8601 timestamp | none | Issues last seen before this time (exclusive). Must be after `lastSeenFrom` when both are given |
| `query` | string | none | Case-insensitive text that must appear in the exception type or the message. Leading and trailing spaces are ignored, and a blank value means no search. `%`, `_` and `\` are matched literally. At most 200 characters, no null characters |
| `sort` | `LAST_SEEN`, `FIRST_SEEN` or `OCCURRENCE_COUNT` | `LAST_SEEN` | Field to sort by |
| `direction` | `ASC` or `DESC` | `DESC` | Sort direction |
| `page` | integer | `0` | Zero-based page number, 0 to 10,000 |
| `size` | integer | `20` | Issues per page, 1 to 100 |

Enum values are case-sensitive. Filters are combined with AND. Timestamps can use `Z` or an offset such as `+02:00`; encode `+` as `%2B` in a URL. The date range applies to an issue's last-seen time: an issue matches if its most recent event arrived in the range.

Sorting is deterministic: after the chosen field, issues are ordered by ID (ascending), so issues with the same sort value always come back in the same order and paging through the results never repeats or skips an issue, as long as the data does not change between requests. A new event moves its issue's last-seen time, so the default order can shift between page requests while events are arriving.

### Response

**`200 OK`**:

```json
{
  "items": [
    {
      "id": "5b1f7c9e-3f4a-4d2b-9a61-0c8e2f3d4a5b",
      "application": "checkout-service",
      "environment": "production",
      "fingerprint": "3c1f0e5a7b9d2f4e6a8c0b1d3f5e7a9c2b4d6f8e0a1c3e5b7d9f1a3c5e7b9d0f",
      "exceptionType": "java.lang.IllegalStateException",
      "message": "Cart is empty",
      "status": "UNRESOLVED",
      "occurrenceCount": 42,
      "firstSeenAt": "2026-10-12T09:00:00.123456Z",
      "lastSeenAt": "2026-10-13T07:45:10.654321Z",
      "resolvedAt": null
    }
  ],
  "page": 0,
  "size": 20,
  "totalItems": 1,
  "totalPages": 1
}
```

`page` and `size` echo the request. `totalItems` counts every issue that matches the filters, and `totalPages` is `totalItems` divided by `size`, rounded up. A page past the end returns `200` with an empty `items` list and the same totals. `message` is the message of the issue's first event, and `resolvedAt` is `null` unless the issue is resolved.

**`400 Bad Request`**: invalid parameters, in the same problem-details shape as for ingestion, with an `errors` list sorted by parameter name. A value that cannot be converted (an unknown `status`, `sort` or `direction`, an unparseable timestamp, a non-numeric `page`) is reported as `has an invalid value`. Conversion errors are reported before range checks, so a request with both kinds of mistake first lists only the conversion errors. The rejected values are not echoed back.

```json
{
  "type": "about:blank",
  "title": "Invalid request",
  "status": 400,
  "detail": "The request contains invalid fields.",
  "instance": "/api/v1/issues",
  "errors": [
    { "field": "lastSeenTo", "message": "must be after lastSeenFrom" }
  ]
}
```

`503` and `500` behave as for ingestion.

### Example

```bash
curl 'http://localhost:8080/api/v1/issues?status=UNRESOLVED&environment=production&query=timeout&size=10'
```

## `GET /api/v1/issues/{id}`

Returns one issue by its ID, in the same shape as an item in the list response.

| Status | When |
|---|---|
| `200 OK` | The issue exists |
| `400 Bad Request` | `id` is not a UUID. The `errors` list has `{ "field": "id", "message": "has an invalid value" }` |
| `404 Not Found` | No issue has this ID. The body is a problem detail with the title `Issue not found` |

```json
{
  "type": "about:blank",
  "title": "Issue not found",
  "status": 404,
  "detail": "No issue exists with this ID.",
  "instance": "/api/v1/issues/7b0c6a3e-0000-4000-8000-000000000000"
}
```

## `GET /api/v1/issues/{id}/events`

Returns the stored events of one issue, newest first, one page at a time.

| Parameter | Type | Default | Rules and meaning |
|---|---|---|---|
| `page` | integer | `0` | Zero-based page number, 0 to 10,000 |
| `size` | integer | `20` | Events per page, 1 to 100 |

Events are ordered by `receivedAt` (the server's receive time), newest first, and then by event ID, descending, so events received in the same microsecond always come back in the same order and paging never repeats or skips an event while no new events arrive. A new event appears at the top of page 0 and shifts the later pages by one. The order does not depend on the client's `occurredAt`, which can be wrong or arrive late.

### Response

**`200 OK`**:

```json
{
  "items": [
    {
      "id": "0d9c8b7a-6f5e-4d3c-8b2a-1f0e9d8c7b6a",
      "exceptionType": "java.lang.IllegalStateException",
      "message": "Cart is empty",
      "stackTrace": "java.lang.IllegalStateException: Cart is empty\n\tat com.example.cart.CartService.checkout(CartService.java:42)",
      "occurredAt": "2026-10-13T09:59:58Z",
      "receivedAt": "2026-10-13T10:00:00.123456Z",
      "correlationId": "req-7f3a",
      "context": { "orderId": "o-1001" }
    }
  ],
  "page": 0,
  "size": 20,
  "totalItems": 1,
  "totalPages": 1
}
```

Each item is the event exactly as it was ingested, with the stack trace as sent (up to 65,536 characters). `message`, `stackTrace`, `correlationId` and `context` are `null` when the client did not send them. Pagination metadata works as for the issue list; a page past the end returns `200` with an empty `items` list. An issue always has at least one event when it is created through ingestion.

| Status | When |
|---|---|
| `200 OK` | The issue exists, even when the requested page is empty |
| `400 Bad Request` | `id` is not a UUID, or `page` or `size` is out of range or not a number |
| `404 Not Found` | No issue has this ID |

## `POST /api/v1/issues/{id}/resolve`

Marks an issue as resolved and records the server's current time as `resolvedAt`. There is no request body. The response is the updated issue, in the same shape as `GET /api/v1/issues/{id}`.

## `POST /api/v1/issues/{id}/reopen`

Sets a resolved issue back to `UNRESOLVED` and clears `resolvedAt`. There is no request body. The response is the updated issue.

### Lifecycle rules

| Current status | `resolve` | `reopen` |
|---|---|---|
| `UNRESOLVED` | `200`, becomes `RESOLVED` | `409 Conflict`, unchanged |
| `RESOLVED` | `409 Conflict`, unchanged | `200`, becomes `UNRESOLVED` |

A new event for a resolved issue also reopens it ([ADR 0003](decisions/0003-event-ingestion.md)).

Repeating a request is predictable: the first request changes the issue, and every repeat gets `409` and changes nothing, so the original `resolvedAt` is kept. The `409` body names the current status, so a client that retries after a lost response can see that the issue is already in the state it asked for:

```json
{
  "type": "about:blank",
  "title": "Invalid status transition",
  "status": 409,
  "detail": "The issue status cannot change from RESOLVED to RESOLVED.",
  "instance": "/api/v1/issues/5b1f7c9e-3f4a-4d2b-9a61-0c8e2f3d4a5b/resolve",
  "currentStatus": "RESOLVED",
  "requestedStatus": "RESOLVED"
}
```

When two requests change the same issue at the same time, or a new event reopens the issue while a request is changing it, exactly one change wins. The other request gets `409` with the title `Concurrent modification` and the detail `The resource was changed by another request. Fetch it again and retry.` Nothing is changed by the losing request.

| Status | When |
|---|---|
| `200 OK` | The status changed |
| `400 Bad Request` | `id` is not a UUID |
| `404 Not Found` | No issue has this ID |
| `409 Conflict` | The issue is already in the requested status, or another change won a race |

```bash
curl -X POST http://localhost:8080/api/v1/issues/5b1f7c9e-3f4a-4d2b-9a61-0c8e2f3d4a5b/resolve
```

## `GET /api/v1/issues/{id}/stats`

Returns occurrence statistics for one issue, calculated from the stored issue and events when the request is made.

```json
{
  "issueId": "5b1f7c9e-3f4a-4d2b-9a61-0c8e2f3d4a5b",
  "occurrenceCount": 42,
  "storedEventCount": 42,
  "firstSeenAt": "2026-10-01T08:00:00.123456Z",
  "lastSeenAt": "2026-10-13T10:00:00.123456Z",
  "calculatedAt": "2026-10-13T10:30:00.654321Z",
  "eventsLast24Hours": 5,
  "eventsLast7Days": 17,
  "dailyEventCounts": [
    { "date": "2026-09-30", "count": 0 },
    { "date": "2026-10-13", "count": 3 }
  ]
}
```

| Field | Meaning |
|---|---|
| `occurrenceCount` | The issue's counter, increased by every ingested event |
| `storedEventCount` | The number of event rows stored for the issue. Equal to `occurrenceCount` today, because every ingested event is stored and nothing deletes events; they can differ once a retention policy exists |
| `firstSeenAt`, `lastSeenAt` | The issue's first and most recent `receivedAt` |
| `calculatedAt` | The server time the statistics were calculated at; every window below ends here |
| `eventsLast24Hours`, `eventsLast7Days` | Events with `receivedAt` at or after `calculatedAt` minus 24 hours or 7 days |
| `dailyEventCounts` | One entry per UTC calendar day for the last 14 days, oldest first and ending with today, including days with no events (shortened in the example above) |

All windows use the server's `receivedAt`, like the issue list, and every value comes from one consistent database snapshot, so the counts agree with each other even while events are arriving.

| Status | When |
|---|---|
| `200 OK` | The issue exists |
| `400 Bad Request` | `id` is not a UUID |
| `404 Not Found` | No issue has this ID |

## Error format

Every error response from `/api/v1` uses the same problem-details shape: `type`, `title`, `status`, `detail` and `instance`, plus `errors` for field validation failures. Responses never include stack traces, exception class names, SQL, or internal host names. Unexpected errors are logged on the server with their stack trace.
