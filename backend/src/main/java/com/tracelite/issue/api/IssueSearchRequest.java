package com.tracelite.issue.api;

import com.tracelite.common.web.IdentifierRules;
import com.tracelite.issue.application.IssueSearchCriteria;
import com.tracelite.issue.application.IssueSortField;
import com.tracelite.issue.domain.Issue;
import com.tracelite.issue.domain.IssueStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.data.domain.Sort;

public record IssueSearchRequest(
        IssueStatus status,

        @Size(max = Issue.MAX_ENVIRONMENT_LENGTH)
        @Pattern(regexp = IdentifierRules.PATTERN, message = IdentifierRules.MESSAGE)
        String environment,

        Instant lastSeenFrom,

        Instant lastSeenTo,

        @Size(max = MAX_QUERY_LENGTH)
        @Pattern(regexp = "[^\\x00]*", message = "must not contain null characters")
        String query,

        IssueSortField sort,

        Sort.Direction direction,

        @Min(0)
        @Max(MAX_PAGE)
        Integer page,

        @Min(1)
        @Max(MAX_PAGE_SIZE)
        Integer size) {

    public static final int MAX_QUERY_LENGTH = 200;
    public static final int MAX_PAGE = 10_000;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_PAGE_SIZE = 20;

    IssueSearchCriteria toCriteria() {
        return new IssueSearchCriteria(status, environment, lastSeenFrom, lastSeenTo, query);
    }

    IssueSortField sortOrDefault() {
        return sort == null ? IssueSortField.LAST_SEEN : sort;
    }

    Sort.Direction directionOrDefault() {
        return direction == null ? Sort.Direction.DESC : direction;
    }

    int pageOrDefault() {
        return page == null ? 0 : page;
    }

    int sizeOrDefault() {
        return size == null ? DEFAULT_PAGE_SIZE : size;
    }
}
