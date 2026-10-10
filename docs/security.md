# Security

## Current state

The backend exposes no business endpoints and has no authentication yet. The controls in place today:

- **Error responses:** Spring Boot's default error responses never include stack traces, exception class names, messages, or binding errors.
- **Actuator:** only `health` and `info` are exposed over HTTP. Health shows only an overall status in the default profile; component details appear only with the `local` profile. Endpoints such as `env`, `configprops`, `beans` and `heapdump` are not exposed, and tests check this.
- **Database credentials:** supplied only through environment variables. There are no default passwords in the code or configuration, and the application does not start without database settings.
- **Local PostgreSQL:** Docker Compose publishes PostgreSQL on `127.0.0.1` only, so it is not reachable from other machines, and it refuses to start without a password.
- **Flyway:** `clean` is disabled, so a misconfiguration cannot drop the schema.
- **Secrets in Git:** `.env` and common key and keystore formats are git-ignored. CI generates a random database password for each run.

## Planned controls

- API-key authentication for ingestion and management endpoints, with constant-time comparison and keys supplied through environment variables.
- Request-size limits and Bean Validation at the API boundary.
- Parameterised queries only, through Spring Data JPA.
- CORS restricted to configured origins once the dashboard is introduced.
- Credentials and sensitive headers excluded from logs.

## Data sensitivity

Stack traces, error messages and event context can contain personal or confidential information such as user identifiers, email addresses, file paths or query parameters. Applications sending events to TraceLite should scrub such data before sending it.

## Limitations

TraceLite is a small-scale portfolio application. Known limitations so far:

- Actuator endpoints share the application port and are unauthenticated. `health` and `info` reveal no configuration, but a production deployment should serve them on a separate management port or network.
- The database connection does not enforce TLS. Configure `sslmode` in `SPRING_DATASOURCE_URL` for any non-local database.
- The application connects with a single database user that also owns the schema. Production use would separate the migration user from the runtime user.

This list will grow as security features are implemented.
