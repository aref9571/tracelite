package com.tracelite.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tracelite.issue.application.InvalidIssueQueryException;
import com.tracelite.issue.application.IssueNotFoundException;
import com.tracelite.issue.application.IssueQueryService;
import com.tracelite.issue.application.IssueSearchCriteria;
import com.tracelite.issue.application.IssueSortField;
import com.tracelite.issue.domain.Issue;
import com.tracelite.issue.persistence.IssueRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

class IssueQueryServiceTest {

    private static final IssueSearchCriteria NO_FILTERS = new IssueSearchCriteria(null, null, null, null, null);

    private final IssueRepository issueRepository = mock(IssueRepository.class);
    private final IssueQueryService service = new IssueQueryService(issueRepository);

    @ParameterizedTest
    @CsvSource({
            "LAST_SEEN, DESC, lastSeenAt",
            "LAST_SEEN, ASC, lastSeenAt",
            "FIRST_SEEN, DESC, firstSeenAt",
            "OCCURRENCE_COUNT, ASC, occurrenceCount"})
    void sortsByTheChosenFieldAndThenByAscendingId(
            IssueSortField sortField, Sort.Direction direction, String property) {
        when(issueRepository.findAll(anySpecification(), any(Pageable.class))).thenReturn(Page.empty());

        service.search(NO_FILTERS, sortField, direction, 3, 25);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(issueRepository).findAll(anySpecification(), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(3);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(25);
        assertThat(pageable.getValue().getSort())
                .containsExactly(new Sort.Order(direction, property), Sort.Order.asc("id"));
    }

    @Test
    void rejectsALastSeenRangeThatEndsBeforeItStarts() {
        Instant from = Instant.parse("2026-10-02T00:00:00Z");
        IssueSearchCriteria criteria = new IssueSearchCriteria(null, null, from, from.minusSeconds(1), null);

        assertThatThrownBy(() -> service.search(criteria, IssueSortField.LAST_SEEN, Sort.Direction.DESC, 0, 20))
                .isInstanceOf(InvalidIssueQueryException.class)
                .hasMessage("lastSeenTo must be after lastSeenFrom");
        verifyNoInteractions(issueRepository);
    }

    @Test
    void returnsAnExistingIssue() {
        UUID id = UUID.randomUUID();
        Issue issue = mock(Issue.class);
        when(issueRepository.findById(id)).thenReturn(Optional.of(issue));

        assertThat(service.getIssue(id)).isSameAs(issue);
    }

    @Test
    void throwsWhenTheIssueDoesNotExist() {
        UUID id = UUID.randomUUID();
        when(issueRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getIssue(id)).isInstanceOf(IssueNotFoundException.class);
    }

    private static Specification<Issue> anySpecification() {
        return any();
    }
}
