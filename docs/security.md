# Security

## Current state

The skeleton exposes no application endpoints and has no authentication. Spring Boot's default error responses are configured to exclude stack traces, exception names, messages, and binding errors.

## Planned controls

- API-key authentication for ingestion and management endpoints, with constant-time comparison and keys supplied through environment variables.
- Request-size limits and Bean Validation at the API boundary.
- Parameterised queries only, through Spring Data JPA.
- CORS restricted to configured origins once the dashboard is introduced.
- Credentials and sensitive headers excluded from logs.

## Data sensitivity

Stack traces, error messages, and event context can contain personal or confidential information such as user identifiers, email addresses, file paths, or query parameters. Applications sending events to TraceLite should scrub such data before sending it.

## Limitations

TraceLite is a small-scale portfolio application. A full list of limitations that would need to be addressed before production use will be maintained here as security features are implemented.
