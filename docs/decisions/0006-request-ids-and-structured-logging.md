# ADR 0006: Request IDs and structured logging

- **Status:** Accepted
- **Date:** 2026-10-17

## Context

When a client reports a failed request, an operator needs to find the log lines that request produced. Until now the logs had no request identifier, the API wrote no log line for ordinary requests, and logs were only in Spring Boot's human-readable format, which log collectors have to parse with patterns. The log content must not grow to include API keys, search text or event payloads.

## Decision

- **A request ID for every request.** `RequestIdFilter` (`common.web`) runs before every other filter, including Spring Security. It takes the `X-Request-Id` header when it is 1 to 64 letters, digits, `.`, `_` or `-`, so a proxy or client can pass its own ID, and otherwise generates a random UUID. Any other value is replaced rather than rejected, so a bad header never fails a request, and the character rule keeps line breaks and other control characters out of the logs.
- **Returned and logged.** The ID is sent back in the `X-Request-Id` response header on every response, including `401`, `403` and `413`, and is put in the SLF4J MDC under `requestId` for the duration of the request, then removed. `logging.pattern.correlation` adds it to every console line as `[id]`.
- **One access line per API request.** After each `/api/**` request the filter logs, at `INFO`, the method, the path, the status and the duration in milliseconds. It never logs the query string, headers or body. Health checks and other non-API paths are not logged, so the container health check does not fill the logs.
- **Structured logs as an option.** Spring Boot's structured logging is enabled with `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` (or `logstash`), which writes one JSON object per line and includes the MDC entries, so `requestId` becomes a field. The human-readable format stays the default so local logs are easy to read.

## Alternatives considered

- **Micrometer Tracing** would add trace and span IDs and could export them to a tracing system. TraceLite is one service with no downstream calls and no tracing backend, so it would add dependencies without adding information.
- **Rejecting invalid `X-Request-Id` values with `400`** would make the header part of the API contract and break clients for a value that only serves logging.

## Consequences

Every log line written during a request can be found from the ID in the response. Clients can choose the ID, so IDs are not guaranteed to be unique; they are a lookup aid, not a security control. The access line adds one log line per API request, which is a cost at high request rates and can be turned off by raising the level of the `com.tracelite.common.web` package, for example with `LOGGING_LEVEL_COM_TRACELITE_COMMON_WEB=WARN`.
