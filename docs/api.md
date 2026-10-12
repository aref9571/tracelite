# API

All endpoints are versioned under `/api/v1`. Requests and responses use JSON; errors use [RFC 9457 problem details](https://www.rfc-editor.org/rfc/rfc9457) (`application/problem+json`).

Authentication is not implemented yet. API-key authentication for these endpoints is planned for Day 8.

## Endpoints

| Method | Path | Purpose | Status |
|---|---|---|---|
| `POST` | `/api/v1/events` | Ingest an error event | Implemented |
| `GET` | `/api/v1/issues` | List, filter, and search issues | Planned |
| `GET` | `/api/v1/issues/{id}` | Retrieve issue details | Planned |
| `GET` | `/api/v1/issues/{id}/events` | Retrieve paginated events for an issue | Planned |
| `POST` | `/api/v1/issues/{id}/resolve` | Mark an issue as resolved | Planned |
| `POST` | `/api/v1/issues/{id}/reopen` | Reopen a resolved issue | Planned |
| `GET` | `/api/v1/issues/{id}/stats` | Retrieve occurrence statistics | Planned |

Planned paths and payloads are provisional and will be documented here as each endpoint is implemented.

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

## Error format

Every error response from `/api/v1` uses the same problem-details shape: `type`, `title`, `status`, `detail` and `instance`, plus `errors` for field validation failures. Responses never include stack traces, exception class names, SQL, or internal host names. Unexpected errors are logged on the server with their stack trace.
