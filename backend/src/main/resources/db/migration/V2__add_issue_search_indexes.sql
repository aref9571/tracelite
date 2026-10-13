CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX issues_exception_type_trgm_idx ON issues USING gin (lower(exception_type) gin_trgm_ops);
CREATE INDEX issues_message_trgm_idx ON issues USING gin (lower(message) gin_trgm_ops);
