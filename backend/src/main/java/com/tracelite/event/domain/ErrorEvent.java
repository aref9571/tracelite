package com.tracelite.event.domain;

import static com.tracelite.common.domain.DomainPreconditions.limitLength;
import static com.tracelite.common.domain.DomainPreconditions.requireText;

import com.tracelite.issue.domain.Issue;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "events")
public class ErrorEvent {

    public static final int MAX_STACK_TRACE_LENGTH = 65_536;
    public static final int MAX_CORRELATION_ID_LENGTH = 128;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "issue_id", nullable = false, updatable = false)
    private Issue issue;

    @Column(name = "exception_type", nullable = false, updatable = false, length = Issue.MAX_EXCEPTION_TYPE_LENGTH)
    private String exceptionType;

    @Column(updatable = false, length = Issue.MAX_MESSAGE_LENGTH)
    private String message;

    @Column(name = "stack_trace", updatable = false, columnDefinition = "text")
    private String stackTrace;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "correlation_id", updatable = false, length = MAX_CORRELATION_ID_LENGTH)
    private String correlationId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> context;

    protected ErrorEvent() {
    }

    public ErrorEvent(
            Issue issue,
            String exceptionType,
            String message,
            String stackTrace,
            Instant occurredAt,
            Instant receivedAt,
            String correlationId,
            Map<String, Object> context) {
        this.issue = Objects.requireNonNull(issue, "issue must not be null");
        this.exceptionType = requireText(exceptionType, "exceptionType", Issue.MAX_EXCEPTION_TYPE_LENGTH);
        this.message = limitLength(message, "message", Issue.MAX_MESSAGE_LENGTH);
        this.stackTrace = limitLength(stackTrace, "stackTrace", MAX_STACK_TRACE_LENGTH);
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt must not be null");
        this.correlationId = limitLength(correlationId, "correlationId", MAX_CORRELATION_ID_LENGTH);
        this.context = context == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(context));
    }

    public UUID getId() {
        return id;
    }

    public Issue getIssue() {
        return issue;
    }

    public String getExceptionType() {
        return exceptionType;
    }

    public String getMessage() {
        return message;
    }

    public String getStackTrace() {
        return stackTrace;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Map<String, Object> getContext() {
        return context;
    }
}
