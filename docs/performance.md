# Performance and reliability review

This document records the Day 13 review of TraceLite's database access, concurrency, limits and logging, the ingestion benchmark and its measured results, and the limitations that remain. Every number here was measured as described; none is a target or a guarantee.

## Ingestion benchmark

`scripts/benchmark/IngestionBenchmark.java` sends error events to `POST /api/v1/events` from a fixed number of concurrent workers and reports the responses by status, the throughput and the latency percentiles. It is a single Java source file with no dependencies, so it runs with the JDK alone. Each event has a 30-frame stack trace (about 2 KB of JSON) and a small `context`; the events are spread over a chosen number of distinct exception types, so the benchmark controls how many issues share the load. A warm-up run is sent first and not reported. The program exits with status 1 if any request did not get `201`.

### Running it

Start PostgreSQL and the packaged backend with an ingestion key (see [development.md](development.md)), then:

```bash
set -a; . ./.env; set +a
export TRACELITE_BENCHMARK_INGESTION_KEY="$TRACELITE_SECURITY_PROJECTS_0_INGESTIONKEY"
java scripts/benchmark/IngestionBenchmark.java \
  --application="$TRACELITE_SECURITY_PROJECTS_0_APPLICATION" \
  --requests=5000 --concurrency=16 --issues=50 --warmup=500
```

| Option | Default | Meaning |
|---|---|---|
| `--application` | required | The application the ingestion key belongs to |
| `--url` | `http://localhost:8080` | Backend base URL |
| `--requests` | 5000 | Measured requests |
| `--concurrency` | 16 | Concurrent workers, each sending one request at a time |
| `--issues` | 50 | Distinct exception types, so distinct issues |
| `--warmup` | 500 | Requests sent before measuring |

The key is read from `TRACELITE_BENCHMARK_INGESTION_KEY` rather than a command-line argument, so it does not appear in the shell history or the process list. The benchmark writes real events, so run it against a local database only and remove its data with `docker compose down --volumes` afterwards.

### Environment of the recorded run (2026-10-17)

| Item | Value |
|---|---|
| Machine | Linux container, 4 vCPUs (Intel Xeon, 2.80 GHz), 15 GiB memory; the benchmark, the backend and PostgreSQL all ran on it |
| Backend | `tracelite-backend.jar` built from this commit, `java -jar` with default JVM settings, OpenJDK 21.0.12, default HikariCP pool (10 connections) |
| Database | PostgreSQL 17.11 (`postgres:17-alpine`) from `docker-compose.yml`, default settings, empty volume at the start |
| Logging | Default configuration (`INFO`, one line per API request) |

The client shares the CPUs with the server, so these figures are lower than a setup with a separate load generator would show, and they will differ on other hardware. They are useful for comparing configurations on the same machine, not as capacity figures.

### Results

All runs used 5,000 measured requests and 500 warm-up requests unless stated, one after another against the same database.

| Concurrency | Distinct issues | Throughput (requests/s) | p50 ms | p95 ms | p99 ms | Max ms | Non-`201` |
|---|---|---|---|---|---|---|---|
| 1 | 50 | 110 | 8.5 | 13.5 | 17.8 | 67.9 | 0 |
| 4 | 50 | 363 | 9.7 | 19.5 | 28.3 | 78.5 | 0 |
| 16 | 50 | 512 | 27.5 | 61.1 | 89.3 | 248.4 | 0 |
| 64 | 50 | 589 | 98.3 | 220.0 | 299.6 | 575.5 | 0 |
| 16 | 1 | 305 | 41.1 | 121.5 | 181.1 | 299.9 | 0 |
| 16 | 10,000 (20,000 requests, no warm-up) | 664 | 21.6 | 45.8 | 61.2 | 348.7 | 0 |

After all runs the database held 10,000 issues and 47,500 events (44 MB for the `events` table and its indexes). For every issue, `occurrence_count` equalled its number of stored events (checked with SQL), so no increment was lost under concurrency.

### What the measurements show

- **A single request takes about 8.5 ms** end to end. Each ingestion is two statements and one commit (see below), and every commit waits for PostgreSQL to flush its write-ahead log.
- **Throughput levels off between 16 and 64 workers** while latency keeps growing: going from 16 to 64 workers added 15% throughput and nearly quadrupled the median latency. The connection pool has 10 connections, so beyond that requests queue for a connection; this is inferred from the pool size, not measured separately.
- **Events for one issue are serialised.** With all 16 workers sending the same issue, throughput fell from 512 to 305 requests per second and p99 doubled, because the `INSERT ... ON CONFLICT DO UPDATE` holds a row lock on the issue until its transaction commits. Spreading the same load over 10,000 issues gave the highest throughput.
- No request failed in any run.

These numbers do not justify asynchronous ingestion or batching for the expected load of a small team's applications ([ADR 0003](decisions/0003-event-ingestion.md)). If a single noisy issue becomes a problem, the hot row is the first thing to address.

## Database review

### Statements per request

Counted with `logging.level.org.hibernate.SQL=DEBUG` against the packaged jar:

| Request | Before | After |
|---|---|---|
| `POST /api/v1/events` | 2: the issue upsert and the event insert | 2 (unchanged) |
| `GET /api/v1/issues` | 1, plus a `count` when there are more issues than fit on the page | unchanged |
| `GET /api/v1/issues/{id}` | 1 | 1 |
| `GET /api/v1/issues/{id}/events` | 2: an existence check, then the page (plus a `count` when the page is full) | 1: the page (plus the `count`); the existence check runs only when the page is empty |
| `GET /api/v1/issues/{id}/stats` | 5: the issue, three separate counts (each joined to `issues`), the daily counts | 3: the issue, one query with all three counts using `FILTER`, the daily counts |
| `POST /api/v1/issues/{id}/resolve` or `/reopen` | 2: select and versioned update | 2 (unchanged) |

Ingestion does not read the issue: the upsert returns its ID and the event refers to it through a JPA reference without loading it. Open-in-view is disabled and no response reads a lazy association, so there are no extra queries while responses are written.

### Query plans

`EXPLAIN ANALYZE` on the benchmark data (10,000 issues, 47,500 events, about 6,000 events for the busiest issue), after `ANALYZE`:

| Query | Plan | Time |
|---|---|---|
| Issue list, default order, first page | Index scan on `issues_last_seen_idx`, stops after 20 rows | 0.06 ms |
| Issue list filtered by status | Index scan on `issues_last_seen_idx` with a filter (every issue was unresolved, so the planner did not need the status index) | 0.05 ms |
| Count of unresolved issues | Sequential scan of `issues` | 3.8 ms |
| Issue list sorted by occurrence count | Sequential scan and top-N sort | 3.1 ms |
| Text search, first page | Index scan on `issues_last_seen_idx` with the `LIKE` filter | 9.2 ms |
| Event page of the busiest issue | Index scan on `events_issue_received_idx`, stops after 20 rows | 0.07 ms |
| Event count of the busiest issue | Index-only scan on `events_issue_received_idx` | 1.1 ms |
| Statistics counts (the new single query) | One index-only scan on `events_issue_received_idx` | 2.4 ms |
| Daily counts for 14 days | Index-only scan and sort | 5.2 ms |

Every query that runs on each request uses an index on the paths that grow with the data. The sorts without a dedicated index (first seen, occurrence count) and the total count take a few milliseconds at 10,000 issues, so no index was added; [database.md](database.md#indexes) records this.

### Transactions and concurrency

- Ingestion runs the upsert and the insert in one transaction; `EventIngestionRollbackTests` proves a failed insert rolls the count back, and the benchmark found no counter drift.
- Simultaneous first events for one fingerprint create one issue (`ConcurrentIngestionTests`); the upsert waits instead of failing, so there are no retries.
- Status changes use optimistic locking, and ingestion bumps the version only when it reopens an issue, so resolves and ingestion do not conflict unnecessarily.
- Event history and statistics run in one `REPEATABLE READ` read-only transaction, so their several queries share a snapshot. The changes above keep that: the existence check and the counts run in the same transaction as before.

### Limits and payload sizes

- Request bodies are limited to 256 KiB, and the event fields to the database column sizes.
- Pages are limited to 100 items and page numbers to 10,000. A page of 100 events can still be large, because each event may carry up to 64 KiB of stack trace and a `context` that fills the rest of a 256 KiB request: in the worst case one response is about 25 MB. The dashboard asks for 10 events per page. This is listed under the known limitations below.
- Offset pagination reads and skips every row before the requested page, so deep pages get slower; the page limit bounds this at 1,000,000 skipped rows.

### Logging and sensitive data

- Every response carries an `X-Request-Id` header, and every log line written while a request is handled includes that ID ([ADR 0006](decisions/0006-request-ids-and-structured-logging.md)).
- Each `/api/**` request is logged once at `INFO` with its method, path, status and duration. The query string, headers and body are never logged, so search text, API keys and event payloads stay out of the logs.
- `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` switches the console to JSON lines (Elastic Common Schema), with the request ID as a `requestId` field.

## Reliability fix: Docker image build

The `main` run for Day 12 failed in the Docker job because the image build downloads Maven through the Maven Wrapper, and that one download from Maven Central failed; the wrapper script fetches the distribution with a single `wget` or `curl` call, which gives up on an HTTP error. The build stage now starts from the official `maven:3.9.11-eclipse-temurin-21-noble` image, which already contains the Maven version the wrapper pins, so the image build no longer downloads Maven itself. Dependencies are still downloaded from Maven Central, by Maven, which retries failed transfers.

## Known limitations

- **Hot issues serialise ingestion.** All events for one issue update the same row, so their transactions queue on its lock (measured above).
- **Ingestion latency follows database commit latency.** Ingestion is synchronous by design; there is no queue or batching.
- **Statistics read every event of an issue.** The counts are index-only scans whose cost grows with the number of events per issue, about 2.4 ms at 6,000 events here. Very busy issues would need pre-aggregated counters.
- **Event pages can be large.** Up to about 25 MB for a page of 100 maximum-size events.
- **Offset pagination** gets slower for deep pages; keyset pagination would avoid this.
- **No retention.** Events are kept until their issue is deleted, and no API deletes data.
- **The benchmark measures one machine.** The client, server and database shared four vCPUs; the numbers are not capacity figures.
- **The backend job in CI still downloads Maven through the wrapper** on every run, so a Maven Central outage can still fail that job; re-running it is the remedy.
