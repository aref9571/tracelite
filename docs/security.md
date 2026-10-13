# Security

## Current state

The backend exposes `POST /api/v1/events`, `GET /api/v1/issues`, `GET /api/v1/issues/{id}`, `GET /api/v1/issues/{id}/events`, `GET /api/v1/issues/{id}/stats`, and `POST /api/v1/issues/{id}/resolve` and `/reopen`, and has no authentication yet (planned for Day 8). The controls in place today:

- **Input validation:** every ingestion field is validated with Bean Validation against the same limits the database enforces. Identifiers (`application`, `environment`, `correlationId`) allow only letters, digits, `.`, `_`, `:` and `-`, which also keeps line breaks and control characters out of anything that may be logged. Null characters are rejected everywhere, including inside `context`. `occurredAt` must fall between 30 days before and 5 minutes after the server's time.
- **Query parameters:** issue-list parameters are validated before any query runs: enums must be known values, timestamps must parse, `page` is limited to 0 to 10,000 and `size` to 1 to 100 so one request cannot load the whole table or overflow the query offset, `environment` follows the identifier rules, and `query` is at most 200 characters without null characters. Search text is passed as a bound parameter and its `LIKE` wildcards are escaped, so a client cannot inject SQL or turn `%` and `_` into wildcards.
- **Request size:** bodies of `/api/**` requests are limited to 256 KiB (configurable). A declared `Content-Length` over the limit is rejected with `413` before the body is read; a chunked body is counted while it is read and rejected as soon as it passes the limit.
- **API errors:** a central exception handler returns RFC 9457 problem details with fixed, generic messages. Responses never include stack traces, exception class names, SQL, constraint names, parser details, host names, or the rejected values. Unexpected errors are logged on the server only.
- **Event history:** `page` is limited to 0 to 10,000 and `size` to 1 to 100. The endpoint returns stored events as they were sent, including stack traces and `context`; these are the client application's data, not TraceLite's own errors, which never appear in responses.
- **Logging:** ingestion does not log event payloads. Stored events are logged at `DEBUG` by ID only; status changes are logged at `INFO` by issue ID only.
- **SQL:** all database access goes through Spring Data JPA, JPA Criteria specifications and parameterised queries; the one native query (`recordOccurrence`) uses bound parameters.
- **Error responses:** Spring Boot's default error responses never include stack traces, exception class names, messages, or binding errors.
- **Actuator:** only `health` and `info` are exposed over HTTP. Health shows only an overall status in the default profile; component details appear only with the `local` profile. Endpoints such as `env`, `configprops`, `beans` and `heapdump` are not exposed, and tests check this.
- **Database credentials:** supplied only through environment variables. There are no default passwords in the code or configuration, and the application does not start without database settings.
- **Local PostgreSQL:** Docker Compose publishes PostgreSQL on `127.0.0.1` only, so it is not reachable from other machines, and it refuses to start without a password.
- **Flyway:** `clean` is disabled, so a misconfiguration cannot drop the schema.
- **Secrets in Git:** `.env` and common key and keystore formats are git-ignored. CI generates a random database password for each run.

## Planned controls

- API-key authentication for ingestion and management endpoints, with constant-time comparison and keys supplied through environment variables.
- CORS restricted to configured origins once the dashboard is introduced.
- Credentials and sensitive headers excluded from logs.

## Data sensitivity

Stack traces, error messages and event context can contain personal or confidential information such as user identifiers, email addresses, file paths or query parameters. Applications sending events to TraceLite should scrub such data before sending it.

## Limitations

TraceLite is a small-scale portfolio application. Known limitations so far:

- Actuator endpoints share the application port and are unauthenticated. `health` and `info` reveal no configuration, but a production deployment should serve them on a separate management port or network.
- The database connection does not enforce TLS. Configure `sslmode` in `SPRING_DATASOURCE_URL` for any non-local database.
- The application connects with a single database user that also owns the schema. Production use would separate the migration user from the runtime user.
- Until Day 8, anyone who can reach the API can submit events, read every issue and event, including stack traces and context that may contain sensitive data, and resolve or reopen any issue. There is no rate limiting, so a client could fill the database.
- The OpenAPI document (`/v3/api-docs`) and Swagger UI (`/swagger-ui.html`) are public. They describe the API but expose no data. Set `SPRINGDOC_API_DOCS_ENABLED=false` and `SPRINGDOC_SWAGGER_UI_ENABLED=false` to turn them off; Day 8 decides whether they stay public.
- `context` accepts any JSON object with up to 50 top-level entries; nesting depth is limited only by the request size and Jackson's default limits.

This list will grow as security features are implemented.
