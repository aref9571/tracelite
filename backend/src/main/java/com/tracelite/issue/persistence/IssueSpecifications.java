package com.tracelite.issue.persistence;

import com.tracelite.issue.domain.Issue;
import com.tracelite.issue.domain.IssueStatus;
import java.time.Instant;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

public final class IssueSpecifications {

    private static final char LIKE_ESCAPE = '\\';

    private IssueSpecifications() {
    }

    public static Specification<Issue> hasStatus(IssueStatus status) {
        return (root, query, builder) -> builder.equal(root.get("status"), status);
    }

    public static Specification<Issue> inEnvironment(String environment) {
        return (root, query, builder) -> builder.equal(root.get("environment"), environment);
    }

    public static Specification<Issue> lastSeenAtOrAfter(Instant from) {
        return (root, query, builder) -> builder.greaterThanOrEqualTo(root.get("lastSeenAt"), from);
    }

    public static Specification<Issue> lastSeenBefore(Instant to) {
        return (root, query, builder) -> builder.lessThan(root.get("lastSeenAt"), to);
    }

    public static Specification<Issue> exceptionTypeOrMessageContains(String text) {
        String pattern = containsPattern(text);
        return (root, query, builder) -> builder.or(
                builder.like(builder.lower(root.get("exceptionType")), pattern, LIKE_ESCAPE),
                builder.like(builder.lower(root.get("message")), pattern, LIKE_ESCAPE));
    }

    static String containsPattern(String text) {
        String escaped = text.strip().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
