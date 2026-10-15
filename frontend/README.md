# TraceLite Dashboard

A React and TypeScript single-page application, built with Vite, for browsing the issues stored by the TraceLite backend. It calls the backend's REST API directly from the browser with the management key.

## What it does

- Asks for the management key and keeps it in the browser tab's session storage.
- Lists issues from `GET /api/v1/issues`, newest first, 20 per page, with the exception type, message, status, environment, application, event count and last-seen time.
- Filters by status and environment; filters and page number are kept in the URL (`/?status=UNRESOLVED&environment=production&page=2`), so a filtered view can be bookmarked and the browser's back button works.
- Opens an issue's summary from `GET /api/v1/issues/{id}` at `/issues/{id}`.
- Shows loading, empty, and error states: no issues yet, no issues matching the filters, a page past the end, an unknown issue, a rejected key (`401`, back to the key form), a key that may not read issues (`403`), invalid parameters (`400` with the field errors), server errors, and a backend that cannot be reached.

Every value on screen comes from the backend; the dashboard has no built-in sample data.

## Requirements

- Node.js 22.22 or later in the 22 line, with npm
- A running TraceLite backend with a management key and the dashboard's origin allowed for CORS (below)

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `VITE_API_BASE_URL` | `http://localhost:8080` | Base URL of the backend. Read when the dev server or build starts and compiled into the build, so it must not contain secrets |

To change it, copy `.env.example` to `.env.local` (ignored by Git) and edit it, or set it in the shell.

The backend must allow the dashboard's origin, because the browser calls the API from another origin. In the backend's `.env`:

```bash
TRACELITE_SECURITY_ALLOWEDORIGINS=http://localhost:5173
```

Add `http://localhost:4173` as well (comma-separated) to use `npm run preview`. Only list origins you control; the backend rejects wildcards. See [docs/security.md](../docs/security.md#current-state).

## Running locally

Start PostgreSQL and the backend as described in the [main README](../README.md#getting-started), with `TRACELITE_SECURITY_ALLOWEDORIGINS` set, then:

```bash
cd frontend
npm ci
npm run dev
```

Open <http://localhost:5173> and enter the value of `TRACELITE_SECURITY_MANAGEMENTKEY`. The dev server always uses port 5173 and stops with an error if it is taken, so the origin always matches the CORS setting.

## Scripts

| Command | What it does |
|---|---|
| `npm run dev` | Starts the Vite dev server on `http://localhost:5173` |
| `npm run lint` | ESLint with the TypeScript and React Hooks rules |
| `npm test` | Unit tests with Vitest (API client, URL filter handling, key format) |
| `npm run build` | Type-checks with `tsc` and builds static files into `dist/` |
| `npm run preview` | Serves `dist/` on `http://localhost:4173` |

## Structure

```
frontend/
├── index.html
├── vite.config.ts           Dev server, preview and Vitest settings
├── eslint.config.js
└── src/
    ├── main.tsx             Entry point and router
    ├── App.tsx              Layout, management key handling and routes
    ├── api/                 API client, response types, base URL and key storage
    ├── components/          Filters, pagination, error notice, key form, status badge
    ├── pages/               Issue list, issue summary and not-found pages
    ├── issueListSearch.ts   Converts between URL search parameters and list queries
    ├── useApiRequest.ts     Loads data, cancels outdated requests and supports retry
    └── format.ts            Date and number formatting
```

## Security notes

- The key is never part of the build. It is typed in by the user, stored in `sessionStorage` for that tab only, sent only in the `X-API-Key` header to `VITE_API_BASE_URL`, and removed on **Change key** or when the backend answers `401`.
- Session storage is readable by any script running on the page, so the dashboard must only be served from a trusted origin. The dashboard has no third-party scripts.
- React escapes every value from the API before rendering it.
