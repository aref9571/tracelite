package com.tracelite.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestcontainersConfiguration.class)
class SchemaConstraintTests {

    private static final String VALID_FINGERPRINT = "'" + "0".repeat(64) + "'";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void acceptsAValidIssueAndEvent() {
        UUID issueId = insertIssue("'checkout-service'", "'production'", VALID_FINGERPRINT, "'UNRESOLVED'", "1",
                "'2026-10-11T09:00:00Z'", "'2026-10-11T09:00:00Z'", "null");

        jdbcTemplate.update("""
                insert into events (issue_id, exception_type, occurred_at, received_at, context)
                values (?, 'java.lang.IllegalStateException', now(), now(), '{"orderId": "A-1001"}')
                """, issueId);

        assertThat(jdbcTemplate.queryForObject("select count(*) from events where issue_id = ?", Long.class, issueId))
                .isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
            blank application              | '  '               | 'production' | '0000000000000000000000000000000000000000000000000000000000000000' | 'UNRESOLVED' | 1 | '2026-10-11T09:00:00Z' | '2026-10-11T09:00:00Z' | null                   | issues_application_not_blank_ck
            blank environment              | 'checkout-service' | ''           | '0000000000000000000000000000000000000000000000000000000000000000' | 'UNRESOLVED' | 1 | '2026-10-11T09:00:00Z' | '2026-10-11T09:00:00Z' | null                   | issues_environment_not_blank_ck
            uppercase fingerprint          | 'checkout-service' | 'production' | 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA' | 'UNRESOLVED' | 1 | '2026-10-11T09:00:00Z' | '2026-10-11T09:00:00Z' | null                   | issues_fingerprint_format_ck
            short fingerprint              | 'checkout-service' | 'production' | 'abc123'                                                           | 'UNRESOLVED' | 1 | '2026-10-11T09:00:00Z' | '2026-10-11T09:00:00Z' | null                   | issues_fingerprint_format_ck
            unknown status                 | 'checkout-service' | 'production' | '0000000000000000000000000000000000000000000000000000000000000000' | 'IGNORED'    | 1 | '2026-10-11T09:00:00Z' | '2026-10-11T09:00:00Z' | null                   | issues_status_ck
            zero occurrences               | 'checkout-service' | 'production' | '0000000000000000000000000000000000000000000000000000000000000000' | 'UNRESOLVED' | 0 | '2026-10-11T09:00:00Z' | '2026-10-11T09:00:00Z' | null                   | issues_occurrence_count_positive_ck
            last seen before first seen    | 'checkout-service' | 'production' | '0000000000000000000000000000000000000000000000000000000000000000' | 'UNRESOLVED' | 1 | '2026-10-11T09:00:00Z' | '2026-10-11T08:59:59Z' | null                   | issues_seen_order_ck
            resolved without resolved_at   | 'checkout-service' | 'production' | '0000000000000000000000000000000000000000000000000000000000000000' | 'RESOLVED'   | 1 | '2026-10-11T09:00:00Z' | '2026-10-11T09:00:00Z' | null                   | issues_resolved_at_matches_status_ck
            unresolved with resolved_at    | 'checkout-service' | 'production' | '0000000000000000000000000000000000000000000000000000000000000000' | 'UNRESOLVED' | 1 | '2026-10-11T09:00:00Z' | '2026-10-11T09:00:00Z' | '2026-10-11T10:00:00Z' | issues_resolved_at_matches_status_ck
            """)
    void rejectsIssuesThatBreakAnInvariant(
            String description,
            String application,
            String environment,
            String fingerprint,
            String status,
            String occurrenceCount,
            String firstSeenAt,
            String lastSeenAt,
            String resolvedAt,
            String constraint) {
        assertThatThrownBy(() -> insertIssue(
                application, environment, fingerprint, status, occurrenceCount, firstSeenAt, lastSeenAt, resolvedAt))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    @Test
    void rejectsAnEventForAnUnknownIssue() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into events (issue_id, exception_type, occurred_at, received_at)
                values (?, 'java.lang.IllegalStateException', now(), now())
                """, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("events_issue_fk");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
            blank exception type    | '  '                              | null                       | null                    | events_exception_type_not_blank_ck
            context is an array     | 'java.lang.IllegalStateException' | null                       | '["a", "b"]'            | events_context_is_object_ck
            context is a scalar     | 'java.lang.IllegalStateException' | null                       | '42'                    | events_context_is_object_ck
            oversized stack trace   | 'java.lang.IllegalStateException' | repeat('x', 65537)         | null                    | events_stack_trace_length_ck
            """)
    void rejectsEventsThatBreakAnInvariant(
            String description, String exceptionType, String stackTrace, String context, String constraint) {
        UUID issueId = insertIssue("'checkout-service'", "'production'", VALID_FINGERPRINT, "'UNRESOLVED'", "1",
                "'2026-10-11T09:00:00Z'", "'2026-10-11T09:00:00Z'", "null");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into events (issue_id, exception_type, stack_trace, occurred_at, received_at, context) "
                        + "values (?, " + exceptionType + ", " + stackTrace + ", now(), now(), " + context + "::jsonb)",
                issueId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    private UUID insertIssue(
            String application,
            String environment,
            String fingerprint,
            String status,
            String occurrenceCount,
            String firstSeenAt,
            String lastSeenAt,
            String resolvedAt) {
        return jdbcTemplate.queryForObject(
                "insert into issues (application, environment, fingerprint, exception_type, status, "
                        + "occurrence_count, first_seen_at, last_seen_at, resolved_at) values ("
                        + String.join(", ", application, environment, fingerprint, "'java.lang.IllegalStateException'",
                                status, occurrenceCount, firstSeenAt + "::timestamptz", lastSeenAt + "::timestamptz",
                                resolvedAt + "::timestamptz")
                        + ") returning id",
                UUID.class);
    }
}
