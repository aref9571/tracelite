# Database

No database is configured yet. PostgreSQL and Flyway are introduced on Day 2, and the schema is designed on Day 3.

## Planned approach

- PostgreSQL is the only supported database.
- All schema changes are versioned Flyway migrations in `backend/src/main/resources/db/migration`, named `V<n>__<description>.sql`. Merged migrations are never edited.
- Two core tables are planned: `issues` (one row per fingerprint group, with status, occurrence count, and first/last-seen timestamps) and `events` (one row per received error, referencing its issue).
- Uniqueness constraints will prevent duplicate issues for the same scoped fingerprint, which is also the basis for safe concurrent ingestion.

This document will describe the actual schema, constraints, indexes, and deletion policies once they exist.
