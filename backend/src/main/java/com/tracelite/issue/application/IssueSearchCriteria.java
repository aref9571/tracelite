package com.tracelite.issue.application;

import com.tracelite.issue.domain.IssueStatus;
import java.time.Instant;

public record IssueSearchCriteria(
        IssueStatus status,
        String environment,
        Instant lastSeenFrom,
        Instant lastSeenTo,
        String text) {
}
