# Security

## Current state

The backend exposes `POST /api/v1/events`, `GET /api/v1/issues`, `GET /api/v1/issues/{id}`, `GET /api/v1/issues/{id}/events`, `GET /api/v1/issues/{id}/stats`, and `POST /api/v1/issues/{id}/resolve` and `/reopen`. All of them need an API key ([ADR 0005](decisions/0005-api-key-authentication.md)). The controls in place today:

- **Authentication:** every `/api/v1` request needs an `X-API-Key` header. Ingestion keys can only submit events for their own application; the management key can only use the issue endpoints. Missing, malformed and unknown keys get `401`, a key used on the wrong endpoint or for another application `403`; nothing reaches a controller or the database before the key is accepted. Paths that are not part of the API, apart from health, info, the OpenAPI document and Swagger UI, are denied.
- **Key handling:** keys come only from configuration; there are no default or committed keys. Only SHA-256 hashes of the keys are kept in memory, and a presented key is compared with every stored hash in constant time (`MessageDigest.isEqual`, no early exit). Malformed or duplicate configured keys stop startup with a message that names the property, never the value.
- **CORS:** cross-origin browser requests get no CORS headers unless their origin is listed in `TRACELITE_SECURITY_ALLOWEDORIGINS`. Listed origins may use `GET` and `POST` with `Content-Type` and `X-API-Key`, without cookies or other credentials. Wildcards and values that are not plain origins are rejected at startup.
- **No sessions:** the API is stateless. It sets no cookies and creates no HTTP session, so CSRF protection is not needed and is turned off. Spring Security adds `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY` and `Cache-Control: no-store` to responses.
- **Input validation:** every ingestion field is validated with Bean Validation against the same limits the database enforces. Identifiers (`application`, `environment`, `correlationId`) allow only letters, digits, `.`, `_`, `:` and `-`, which also keeps line breaks and control characters out of anything that may be logged. Null characters are rejected everywhere, including inside `context`. `occurredAt` must fall between 30 days before and 5 minutes after the server's time.
- **Query parameters:** issue-list parameters are validated before any query runs: enums must be known values, timestamps must parse, `page` is limited to 0 to 10,000 and `size` to 1 to 100 so one request cannot load the whole table or overflow the query offset, `environment` follows the identifier rules, and `query` is at most 200 characters without null characters. Search text is passed as a bound parameter and its `LIKE` wildcards are escaped, so a client cannot inject SQL or turn `%` and `_` into wildcards.
- **Request size:** bodies of `/api/**` requests are limited to 256 KiB (configurable). A declared `Content-Length` over the limit is rejected with `413` before the body is read; a chunked body is counted while it is read and rejected as soon as it passes the limit. Unauthenticated requests are rejected before the body is read at all. Request headers are limited by Tomcat's default of 8 KiB.
- **API errors:** a central exception handler returns RFC 9457 problem details with fixed, generic messages. Responses never include stack traces, exception class names, SQL, constraint names, parser details, host names, or the rejected values. Unexpected errors are logged on the server only.
- **Event history:** `page` is limited to 0 to 10,000 and `size` to 1 to 100. The endpoint returns stored events as they were sent, including stack traces and `context`; these are the client application's data, not TraceLite's own errors, which never appear in responses.
- **Logging:** each `/api/**` request is logged once with its method, path, status, duration and request ID; the query string, headers and body are never logged. A client-supplied `X-Request-Id` is used only if it consists of 1 to 64 letters, digits, `.`, `_` or `-`, so it cannot inject line breaks into the logs. Ingestion does not log event payloads. Stored events are logged at `DEBUG` by ID only; status changes are logged at `INFO` by issue ID only. API keys and request headers are never logged: a rejected key is logged at `DEBUG` with only the method and path, and a test checks that no key appears in the output even with Spring Security's `TRACE` logging. The configuration object's `toString` redacts the keys.
- **SQL:** all database access goes through Spring Data JPA, JPA Criteria specifications and parameterised queries; the one native query (`recordOccurrence`) uses bound parameters.
- **Error responses:** Spring Boot's default error responses never include stack traces, exception class names, messages, or binding errors.
- **Actuator:** only `health` and `info` are exposed over HTTP, and they are the only public actuator paths; any other actuator path gets `401` or `403` from the security rules before actuator is consulted. Health shows only an overall status in the default profile; component details appear only with the `local` profile. Endpoints such as `env`, `configprops`, `beans` and `heapdump` are not exposed, and tests check this.
- **Database credentials:** supplied only through environment variables. There are no default passwords in the code or configuration, and the application does not start without database settings.
- **Local PostgreSQL:** Docker Compose publishes PostgreSQL on `127.0.0.1` only, so it is not reachable from other machines, and it refuses to start without a password.
- **Container image:** the backend image runs as an unprivileged system user, and its application files are owned by root and read-only to that user. `.dockerignore` lets only the build files and `backend/src/main` into the build, so `.env` and other local files cannot end up in a layer. No secret is set in the Dockerfile; passwords and keys are passed as environment variables when the container starts. The Compose stack publishes the backend on `127.0.0.1` only. CI checks the image for the generated secrets and for `.env` files on every run. Environment variables can still be read by anyone who can run `docker inspect` on the host, so access to the Docker daemon must be treated as access to the secrets.
- **Dashboard:** the management key is entered by the user, kept only in the browser tab's `sessionStorage`, sent only in the `X-API-Key` header to the configured backend, and removed on **Change key** or after a `401`. It is never part of the build; `VITE_API_BASE_URL` is the only build-time setting. The dashboard loads no third-party scripts and renders API values as text, never as HTML.
- **Flyway:** `clean` is disabled, so a misconfiguration cannot drop the schema.
- **Secrets in Git:** `.env` and common key and keystore formats are git-ignored. CI generates a random database password and random API keys for each run. The fixed keys in `backend/src/test/resources/config/application.yml` are test data, only on the test classpath, and are valid nowhere else.

## API keys

### Generating keys

A key must be 32 to 128 characters of `A-Z`, `a-z`, `0-9`, `_` or `-`. Generate each key separately from a secure random source, for example:

```bash
openssl rand -hex 32                                   # 64 hex characters
python3 -c 'import secrets; print(secrets.token_urlsafe(32))'
```

Never reuse a key for two purposes, never share an ingestion key between applications, and never commit a key or put it in an issue, log or screenshot. Two equal configured keys stop startup.

### Configuring keys

Keys are environment variables (see `.env.example` and [development.md](development.md#configuration)):

```bash
TRACELITE_SECURITY_MANAGEMENTKEY=<management key>
TRACELITE_SECURITY_PROJECTS_0_APPLICATION=checkout-service
TRACELITE_SECURITY_PROJECTS_0_INGESTIONKEY=<ingestion key for checkout-service>
TRACELITE_SECURITY_PROJECTS_1_APPLICATION=billing-service
TRACELITE_SECURITY_PROJECTS_1_INGESTIONKEY=<ingestion key for billing-service>
```

The application name must match the `application` field of the events that key sends. Each application should receive only its own ingestion key; the management key belongs to operators and the dashboard. Without any keys the backend starts but rejects every API call and logs a warning.

### Rotating and revoking keys

- **Rotate an ingestion key:** add a second entry with the same application and a new key, restart, switch the application to the new key, then remove the old entry and restart again.
- **Rotate the management key:** set the new value and restart; clients using the old key get `401` from then on.
- **Revoke a key:** remove its entry and restart.

## Data sensitivity

Stack traces, error messages and event context can contain personal or confidential information such as user identifiers, email addresses, file paths or query parameters. Applications sending events to TraceLite should scrub such data before sending it.

## Limitations

TraceLite is a small-scale portfolio application and is not hardened for production. Known limitations:

- **Static, shared keys.** Keys live in environment variables, so adding, rotating or revoking one needs a restart. There is a single management key, so actions cannot be attributed to a person, and every holder can read all applications' events and resolve any issue. A production system would need user accounts (for example OpenID Connect), per-user permissions and an audit log.
- **No transport security in the application.** Keys are sent in a header and are readable by anyone who can see the traffic. Any deployment beyond `localhost` must sit behind TLS (a reverse proxy or a TLS-enabled server).
- **No rate limiting or lock-out.** Failed attempts are not throttled. Keys are long and random, so guessing is not practical, but a client with a valid ingestion key can send events as fast as the server accepts them and fill the database. A production deployment needs rate limits per key and retention limits.
- **An ingestion key is trusted for its application's environments.** It can send events for any environment of its application, and a key embedded in a client application should be treated as public.
- **Keys are compared in constant time, but are not hashed with a slow algorithm.** Only in-memory SHA-256 hashes are kept; this is enough because keys are random and never stored on disk by TraceLite, unlike passwords.
- **The dashboard key is readable by scripts on the page.** `sessionStorage` is cleared when the tab closes but is not protected from cross-site scripting, so the dashboard must be served only from a trusted origin. A production deployment would use short-lived user tokens instead of the shared management key.
- Actuator endpoints share the application port. `health` and `info` are public and reveal no configuration, but a production deployment should serve them on a separate management port or network.
- The database connection does not enforce TLS. Configure `sslmode` in `SPRING_DATASOURCE_URL` for any non-local database.
- The application connects with a single database user that also owns the schema. Production use would separate the migration user from the runtime user.
- The OpenAPI document (`/v3/api-docs`) and Swagger UI (`/swagger-ui.html`) are public. They describe the API but expose no data. Set `SPRINGDOC_API_DOCS_ENABLED=false` and `SPRINGDOC_SWAGGER_UI_ENABLED=false` to turn them off.
- `context` accepts any JSON object with up to 50 top-level entries; nesting depth is limited only by the request size and Jackson's default limits.
- Stored stack traces and context are not encrypted at rest beyond what the database storage provides.
