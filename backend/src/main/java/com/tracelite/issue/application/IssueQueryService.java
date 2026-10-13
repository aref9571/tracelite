package com.tracelite.issue.application;

import com.tracelite.issue.domain.Issue;
import com.tracelite.issue.persistence.IssueRepository;
import com.tracelite.issue.persistence.IssueSpecifications;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class IssueQueryService {

    private static final String ID_PROPERTY = "id";

    private final IssueRepository issueRepository;

    public IssueQueryService(IssueRepository issueRepository) {
        this.issueRepository = issueRepository;
    }

    public Page<Issue> search(
            IssueSearchCriteria criteria, IssueSortField sortField, Sort.Direction direction, int page, int size) {
        requireValidLastSeenRange(criteria);
        Sort sort = Sort.by(direction, sortField.getProperty()).and(Sort.by(Sort.Direction.ASC, ID_PROPERTY));
        return issueRepository.findAll(toSpecification(criteria), PageRequest.of(page, size, sort));
    }

    public Issue getIssue(UUID issueId) {
        return issueRepository.findById(issueId).orElseThrow(() -> new IssueNotFoundException(issueId));
    }

    private static Specification<Issue> toSpecification(IssueSearchCriteria criteria) {
        List<Specification<Issue>> specifications = new ArrayList<>();
        if (criteria.status() != null) {
            specifications.add(IssueSpecifications.hasStatus(criteria.status()));
        }
        if (criteria.environment() != null) {
            specifications.add(IssueSpecifications.inEnvironment(criteria.environment()));
        }
        if (criteria.lastSeenFrom() != null) {
            specifications.add(IssueSpecifications.lastSeenAtOrAfter(criteria.lastSeenFrom()));
        }
        if (criteria.lastSeenTo() != null) {
            specifications.add(IssueSpecifications.lastSeenBefore(criteria.lastSeenTo()));
        }
        if (criteria.text() != null && !criteria.text().isBlank()) {
            specifications.add(IssueSpecifications.exceptionTypeOrMessageContains(criteria.text()));
        }
        return Specification.allOf(specifications);
    }

    private static void requireValidLastSeenRange(IssueSearchCriteria criteria) {
        if (criteria.lastSeenFrom() != null
                && criteria.lastSeenTo() != null
                && !criteria.lastSeenTo().isAfter(criteria.lastSeenFrom())) {
            throw new InvalidIssueQueryException("lastSeenTo", "must be after lastSeenFrom");
        }
    }
}
