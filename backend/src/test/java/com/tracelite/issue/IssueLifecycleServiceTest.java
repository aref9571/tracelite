package com.tracelite.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tracelite.issue.application.IssueLifecycleService;
import com.tracelite.issue.application.IssueNotFoundException;
import com.tracelite.issue.domain.InvalidIssueStatusTransitionException;
import com.tracelite.issue.domain.Issue;
import com.tracelite.issue.domain.IssueStatus;
import com.tracelite.issue.persistence.IssueRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IssueLifecycleServiceTest {

    private static final UUID ISSUE_ID = UUID.randomUUID();
    private static final Instant FIRST_SEEN = Instant.parse("2026-10-13T08:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-13T10:15:30.123456789Z");

    private final IssueRepository issueRepository = mock(IssueRepository.class);
    private final IssueLifecycleService service =
            new IssueLifecycleService(issueRepository, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void resolvesAnUnresolvedIssueAtTheCurrentTimeInMicroseconds() {
        Issue issue = storedIssue();
        when(issueRepository.saveAndFlush(issue)).thenReturn(issue);

        Issue resolved = service.resolve(ISSUE_ID);

        assertThat(resolved.getStatus()).isEqualTo(IssueStatus.RESOLVED);
        assertThat(resolved.getResolvedAt()).isEqualTo(Instant.parse("2026-10-13T10:15:30.123456Z"));
        verify(issueRepository).saveAndFlush(issue);
    }

    @Test
    void reopensAResolvedIssue() {
        Issue issue = storedIssue();
        issue.resolve(FIRST_SEEN.plusSeconds(60));
        when(issueRepository.saveAndFlush(issue)).thenReturn(issue);

        Issue reopened = service.reopen(ISSUE_ID);

        assertThat(reopened.getStatus()).isEqualTo(IssueStatus.UNRESOLVED);
        assertThat(reopened.getResolvedAt()).isNull();
        verify(issueRepository).saveAndFlush(issue);
    }

    @Test
    void rejectsResolvingAnAlreadyResolvedIssueWithoutSaving() {
        Issue issue = storedIssue();
        Instant firstResolution = FIRST_SEEN.plusSeconds(60);
        issue.resolve(firstResolution);

        assertThatThrownBy(() -> service.resolve(ISSUE_ID))
                .isInstanceOfSatisfying(InvalidIssueStatusTransitionException.class, exception -> {
                    assertThat(exception.getCurrentStatus()).isEqualTo(IssueStatus.RESOLVED);
                    assertThat(exception.getTargetStatus()).isEqualTo(IssueStatus.RESOLVED);
                });
        assertThat(issue.getResolvedAt()).isEqualTo(firstResolution);
        verify(issueRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsReopeningAnUnresolvedIssueWithoutSaving() {
        storedIssue();

        assertThatThrownBy(() -> service.reopen(ISSUE_ID))
                .isInstanceOfSatisfying(InvalidIssueStatusTransitionException.class, exception -> {
                    assertThat(exception.getCurrentStatus()).isEqualTo(IssueStatus.UNRESOLVED);
                    assertThat(exception.getTargetStatus()).isEqualTo(IssueStatus.UNRESOLVED);
                });
        verify(issueRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsChangesToAnUnknownIssue() {
        when(issueRepository.findById(ISSUE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve(ISSUE_ID)).isInstanceOf(IssueNotFoundException.class);
        assertThatThrownBy(() -> service.reopen(ISSUE_ID)).isInstanceOf(IssueNotFoundException.class);
        verify(issueRepository, never()).saveAndFlush(any());
    }

    private Issue storedIssue() {
        Issue issue = new Issue(
                "checkout-service",
                "production",
                "ab".repeat(32),
                "java.lang.IllegalStateException",
                "Cart is empty",
                FIRST_SEEN);
        when(issueRepository.findById(ISSUE_ID)).thenReturn(Optional.of(issue));
        return issue;
    }
}
