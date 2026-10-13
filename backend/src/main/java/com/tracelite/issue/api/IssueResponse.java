package com.tracelite.issue.api;

import com.tracelite.issue.domain.Issue;
import com.tracelite.issue.domain.IssueStatus;
import java.time.Instant;
import java.util.UUID;

public record IssueResponse(
        UUID id,
        String application,
        String environment,
        String fingerprint,
        String exceptionType,
        String message,
        IssueStatus status,
        long occurrenceCount,
        Instant firstSeenAt,
        Instant lastSeenAt,
        Instant resolvedAt) {

    static IssueResponse from(Issue issue) {
        return new IssueResponse(
                issue.getId(),
                issue.getApplication(),
                issue.getEnvironment(),
                issue.getFingerprint(),
                issue.getExceptionType(),
                issue.getMessage(),
                issue.getStatus(),
                issue.getOccurrenceCount(),
                issue.getFirstSeenAt(),
                issue.getLastSeenAt(),
                issue.getResolvedAt());
    }
}
