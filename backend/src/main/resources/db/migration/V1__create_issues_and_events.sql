CREATE TABLE issues (
    id               uuid         NOT NULL DEFAULT gen_random_uuid(),
    application      varchar(100) NOT NULL,
    environment      varchar(50)  NOT NULL,
    fingerprint      varchar(64)  NOT NULL,
    exception_type   varchar(255) NOT NULL,
    message          varchar(4000),
    status           varchar(16)  NOT NULL,
    occurrence_count bigint       NOT NULL,
    first_seen_at    timestamptz  NOT NULL,
    last_seen_at     timestamptz  NOT NULL,
    resolved_at      timestamptz,
    version          bigint       NOT NULL DEFAULT 0,

    CONSTRAINT issues_pk PRIMARY KEY (id),
    CONSTRAINT issues_scope_fingerprint_uk UNIQUE (application, environment, fingerprint),
    CONSTRAINT issues_application_not_blank_ck CHECK (btrim(application) <> ''),
    CONSTRAINT issues_environment_not_blank_ck CHECK (btrim(environment) <> ''),
    CONSTRAINT issues_exception_type_not_blank_ck CHECK (btrim(exception_type) <> ''),
    CONSTRAINT issues_fingerprint_format_ck CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT issues_status_ck CHECK (status IN ('UNRESOLVED', 'RESOLVED')),
    CONSTRAINT issues_occurrence_count_positive_ck CHECK (occurrence_count >= 1),
    CONSTRAINT issues_seen_order_ck CHECK (last_seen_at >= first_seen_at),
    CONSTRAINT issues_resolved_at_matches_status_ck CHECK ((status = 'RESOLVED') = (resolved_at IS NOT NULL))
);

CREATE INDEX issues_status_last_seen_idx ON issues (status, last_seen_at DESC, id);
CREATE INDEX issues_environment_last_seen_idx ON issues (environment, last_seen_at DESC, id);
CREATE INDEX issues_last_seen_idx ON issues (last_seen_at DESC, id);

CREATE TABLE events (
    id             uuid         NOT NULL DEFAULT gen_random_uuid(),
    issue_id       uuid         NOT NULL,
    exception_type varchar(255) NOT NULL,
    message        varchar(4000),
    stack_trace    text,
    occurred_at    timestamptz  NOT NULL,
    received_at    timestamptz  NOT NULL,
    correlation_id varchar(128),
    context        jsonb,

    CONSTRAINT events_pk PRIMARY KEY (id),
    CONSTRAINT events_issue_fk FOREIGN KEY (issue_id) REFERENCES issues (id) ON DELETE CASCADE,
    CONSTRAINT events_exception_type_not_blank_ck CHECK (btrim(exception_type) <> ''),
    CONSTRAINT events_stack_trace_length_ck CHECK (char_length(stack_trace) <= 65536),
    CONSTRAINT events_context_is_object_ck CHECK (context IS NULL OR jsonb_typeof(context) = 'object')
);

CREATE INDEX events_issue_received_idx ON events (issue_id, received_at DESC, id DESC);
