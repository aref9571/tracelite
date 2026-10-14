# ADR 0005: API-key authentication

- **Status:** Accepted
- **Date:** 2026-10-14

## Context

Until now anyone who could reach the backend could submit events, read every issue and event (stack traces and context can hold personal or confidential data), and resolve or reopen issues. The master plan asks for project-specific API credentials, protected ingestion and management operations, safe comparison, no credentials in logs, and CORS restricted to configured origins. It also says not to build a user-account system unless it becomes necessary.

## Decision

- **Two kinds of static API keys, sent in the `X-API-Key` header.**
  - An **ingestion key** belongs to one application (`tracelite.security.projects[n]`). It may only call `POST /api/v1/events`, and only for events whose `application` matches its own; an event for another application gets `403`. An application may have several keys, so a key can be rotated without downtime.
  - The **management key** (`tracelite.security.management-key`) may call every `/api/v1/issues/**` endpoint (list, details, events, stats, resolve, reopen) and nothing else, so a leaked management key cannot inject events and a leaked ingestion key cannot read data.
- **Configuration only through properties.** Keys come from environment variables (`TRACELITE_SECURITY_MANAGEMENTKEY`, `TRACELITE_SECURITY_PROJECTS_0_APPLICATION`, `TRACELITE_SECURITY_PROJECTS_0_INGESTIONKEY`, ...). There are no default keys. Without keys the backend starts but rejects every `/api/**` request and logs a warning, so a missing variable fails closed. Keys must be 32 to 128 characters of `A-Z a-z 0-9 _ -` (for example `openssl rand -hex 32`), must all differ, and a malformed key stops startup with a message that names the property but not the value.
- **Comparison.** At startup each key is hashed with SHA-256 and only the hashes are kept. A presented key is hashed and compared with every stored hash using `MessageDigest.isEqual`, without stopping at the first match, so the time taken does not depend on how much of a key matched or which key it was.
- **Spring Security, stateless.** A `SecurityFilterChain` with a custom filter sets the authentication; sessions, CSRF protection, form login, HTTP Basic and the request cache are off, because no cookies or browser sessions are involved. CSRF attacks need ambient credentials such as cookies; a custom header cannot be sent cross-site without passing CORS.
- **Responses.** A missing, malformed or unknown key gets `401` with `WWW-Authenticate: ApiKey header="X-API-Key"`; a valid key on an endpoint it may not use gets `403`. Both are problem details with fixed text that never echoes the key. All three `401` cases share one message, so a client cannot tell a malformed key from an unknown one.
- **Public endpoints.** `/actuator/health/**`, `/actuator/info`, `/v3/api-docs/**` and Swagger UI stay public: they reveal no stored data, and the OpenAPI document now declares the API-key scheme so Swagger UI can send a key. Both documents can still be switched off with `SPRINGDOC_API_DOCS_ENABLED` and `SPRINGDOC_SWAGGER_UI_ENABLED`. Every other path is denied.
- **CORS.** Cross-origin browser requests are refused unless origins are listed in `tracelite.security.allowed-origins`. Listed origins may use `GET` and `POST` with the `Content-Type` and `X-API-Key` headers, without credentials. Wildcards are rejected at startup. The dashboard (Day 11) will configure its development origin.

## Alternatives considered

- **Keys stored in the database with a management API.** Needed for self-service projects, but it adds a schema, key-creation endpoints and an admin credential to bootstrap them. Static configuration is enough for a small installation and keeps secrets out of the database.
- **OAuth 2.0 / OpenID Connect.** The right choice for a multi-user product, but it needs an identity provider and user accounts, which the plan rules out for the initial release.
- **`Authorization: Bearer <key>`.** Equally valid; a dedicated header was chosen because these are not OAuth tokens and the separate header is clearer in documentation and Swagger UI.

## Consequences

Adding, rotating or revoking a key means changing environment variables and restarting. There is one management key, so every dashboard or operator shares it and actions cannot be attributed to a person. Keys travel in a header, so any deployment beyond `localhost` must use TLS. There is no rate limiting or lock-out after failed attempts; keys are long and random enough that guessing is not practical, but a client with a valid ingestion key can still fill the database. These limits are listed in [security.md](../security.md#limitations).
