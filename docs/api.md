# API

No API endpoints are implemented yet.

## Planned endpoints

All endpoints will be versioned under `/api/v1`.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/events` | Ingest an error event |
| `GET` | `/api/v1/issues` | List, filter, and search issues |
| `GET` | `/api/v1/issues/{id}` | Retrieve issue details |
| `GET` | `/api/v1/issues/{id}/events` | Retrieve paginated events for an issue |
| `POST` | `/api/v1/issues/{id}/resolve` | Mark an issue as resolved |
| `POST` | `/api/v1/issues/{id}/reopen` | Reopen a resolved issue |
| `GET` | `/api/v1/issues/{id}/stats` | Retrieve occurrence statistics |

Paths and payloads are provisional and will be documented here with examples as each endpoint is implemented.
