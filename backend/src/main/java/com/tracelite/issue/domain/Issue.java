package com.tracelite.issue.domain;

import static com.tracelite.common.domain.DomainPreconditions.limitLength;
import static com.tracelite.common.domain.DomainPreconditions.requireText;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Entity
@Table(name = "issues")
public class Issue {

    public static final int MAX_APPLICATION_LENGTH = 100;
    public static final int MAX_ENVIRONMENT_LENGTH = 50;
    public static final int MAX_EXCEPTION_TYPE_LENGTH = 255;
    public static final int MAX_MESSAGE_LENGTH = 4000;

    private static final Pattern FINGERPRINT_FORMAT = Pattern.compile("[0-9a-f]{64}");

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false, length = MAX_APPLICATION_LENGTH)
    private String application;

    @Column(nullable = false, updatable = false, length = MAX_ENVIRONMENT_LENGTH)
    private String environment;

    @Column(nullable = false, updatable = false, length = 64)
    private String fingerprint;

    @Column(name = "exception_type", nullable = false, updatable = false, length = MAX_EXCEPTION_TYPE_LENGTH)
    private String exceptionType;

    @Column(updatable = false, length = MAX_MESSAGE_LENGTH)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IssueStatus status;

    @Column(name = "occurrence_count", nullable = false, updatable = false)
    private long occurrenceCount;

    @Column(name = "first_seen_at", nullable = false, updatable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false, updatable = false)
    private Instant lastSeenAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected Issue() {
    }

    public Issue(
            String application,
            String environment,
            String fingerprint,
            String exceptionType,
            String message,
            Instant firstSeenAt) {
        this.application = requireText(application, "application", MAX_APPLICATION_LENGTH);
        this.environment = requireText(environment, "environment", MAX_ENVIRONMENT_LENGTH);
        this.fingerprint = requireFingerprint(fingerprint);
        this.exceptionType = requireText(exceptionType, "exceptionType", MAX_EXCEPTION_TYPE_LENGTH);
        this.message = limitLength(message, "message", MAX_MESSAGE_LENGTH);
        this.firstSeenAt = Objects.requireNonNull(firstSeenAt, "firstSeenAt must not be null");
        this.lastSeenAt = firstSeenAt;
        this.occurrenceCount = 1;
        this.status = IssueStatus.UNRESOLVED;
    }

    public void resolve(Instant resolvedAt) {
        Objects.requireNonNull(resolvedAt, "resolvedAt must not be null");
        transitionTo(IssueStatus.RESOLVED);
        this.resolvedAt = resolvedAt;
    }

    public void reopen() {
        transitionTo(IssueStatus.UNRESOLVED);
        this.resolvedAt = null;
    }

    private void transitionTo(IssueStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidIssueStatusTransitionException(status, target);
        }
        this.status = target;
    }

    private static String requireFingerprint(String value) {
        if (value == null || !FINGERPRINT_FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("fingerprint must be 64 lowercase hexadecimal characters");
        }
        return value;
    }

    public UUID getId() {
        return id;
    }

    public String getApplication() {
        return application;
    }

    public String getEnvironment() {
        return environment;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getExceptionType() {
        return exceptionType;
    }

    public String getMessage() {
        return message;
    }

    public IssueStatus getStatus() {
        return status;
    }

    public long getOccurrenceCount() {
        return occurrenceCount;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public long getVersion() {
        return version;
    }
}
