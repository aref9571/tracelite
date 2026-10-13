package com.tracelite.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tracelite.config.IngestionConfiguration;
import com.tracelite.issue.api.IssueController;
import com.tracelite.issue.application.InvalidIssueQueryException;
import com.tracelite.issue.application.IssueLifecycleService;
import com.tracelite.issue.application.IssueNotFoundException;
import com.tracelite.issue.application.IssueQueryService;
import com.tracelite.issue.application.IssueSearchCriteria;
import com.tracelite.issue.application.IssueSortField;
import com.tracelite.issue.domain.InvalidIssueStatusTransitionException;
import com.tracelite.issue.domain.Issue;
import com.tracelite.issue.domain.IssueStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(IssueController.class)
@Import(IngestionConfiguration.class)
class IssueControllerTests {

    private static final String ISSUES_PATH = "/api/v1/issues";
    private static final UUID ISSUE_ID = UUID.fromString("5b1f7c9e-3f4a-4d2b-9a61-0c8e2f3d4a5b");
    private static final Instant FIRST_SEEN = Instant.parse("2026-10-12T09:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IssueQueryService issueQueryService;

    @MockitoBean
    private IssueLifecycleService issueLifecycleService;

    @Test
    void listsIssuesWithDefaultsWhenNoParametersAreGiven() throws Exception {
        when(issueQueryService.search(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(storedIssue()), PageRequest.of(0, 20), 1));

        mockMvc.perform(get(ISSUES_PATH))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(ISSUE_ID.toString()))
                .andExpect(jsonPath("$.items[0].application").value("checkout-service"))
                .andExpect(jsonPath("$.items[0].environment").value("production"))
                .andExpect(jsonPath("$.items[0].fingerprint").value("ab".repeat(32)))
                .andExpect(jsonPath("$.items[0].exceptionType").value("java.lang.IllegalStateException"))
                .andExpect(jsonPath("$.items[0].message").value("Cart is empty"))
                .andExpect(jsonPath("$.items[0].status").value("UNRESOLVED"))
                .andExpect(jsonPath("$.items[0].occurrenceCount").value(1))
                .andExpect(jsonPath("$.items[0].firstSeenAt").value("2026-10-12T09:00:00Z"))
                .andExpect(jsonPath("$.items[0].lastSeenAt").value("2026-10-12T09:00:00Z"))
                .andExpect(jsonPath("$.items[0].resolvedAt").value(nullValue()))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.items[0].version").doesNotExist());

        verify(issueQueryService).search(
                new IssueSearchCriteria(null, null, null, null, null),
                IssueSortField.LAST_SEEN,
                Sort.Direction.DESC,
                0,
                20);
    }

    @Test
    void passesEveryQueryParameterToTheService() throws Exception {
        when(issueQueryService.search(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 5), 11));

        mockMvc.perform(get(ISSUES_PATH)
                        .param("status", "RESOLVED")
                        .param("environment", "production")
                        .param("lastSeenFrom", "2026-10-01T00:00:00Z")
                        .param("lastSeenTo", "2026-10-02T12:00:00+02:00")
                        .param("query", "timeout")
                        .param("sort", "OCCURRENCE_COUNT")
                        .param("direction", "ASC")
                        .param("page", "2")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalItems").value(11))
                .andExpect(jsonPath("$.totalPages").value(3));

        ArgumentCaptor<IssueSearchCriteria> criteria = ArgumentCaptor.forClass(IssueSearchCriteria.class);
        verify(issueQueryService).search(
                criteria.capture(), eq(IssueSortField.OCCURRENCE_COUNT), eq(Sort.Direction.ASC), eq(2), eq(5));
        assertThat(criteria.getValue()).isEqualTo(new IssueSearchCriteria(
                IssueStatus.RESOLVED,
                "production",
                Instant.parse("2026-10-01T00:00:00Z"),
                Instant.parse("2026-10-02T10:00:00Z"),
                "timeout"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            status       | OPEN                  | has an invalid value
            status       | unresolved            | has an invalid value
            sort         | lastSeenAt            | has an invalid value
            direction    | down                  | has an invalid value
            lastSeenFrom | yesterday             | has an invalid value
            lastSeenTo   | 2026-10-01            | has an invalid value
            page         | -1                    | must be greater than or equal to 0
            page         | 10001                 | must be less than or equal to 10000
            page         | first                 | has an invalid value
            size         | 0                     | must be greater than or equal to 1
            size         | 101                   | must be less than or equal to 100
            environment  | prod env              | must contain only letters, digits, '.', '_', ':' or '-'
            environment  | ''                    | must contain only letters, digits, '.', '_', ':' or '-'
            """)
    void rejectsAnInvalidQueryParameter(String parameter, String value, String message) throws Exception {
        mockMvc.perform(get(ISSUES_PATH).param(parameter, value))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value(parameter))
                .andExpect(jsonPath("$.errors[0].message").value(message))
                .andExpect(content().string(not(containsString("java."))))
                .andExpect(content().string(not(containsString("Failed to convert"))));

        verifyNoInteractions(issueQueryService);
    }

    @Test
    void rejectsAnOverlongEnvironmentOrSearchText() throws Exception {
        mockMvc.perform(get(ISSUES_PATH)
                        .param("environment", "e".repeat(Issue.MAX_ENVIRONMENT_LENGTH + 1))
                        .param("query", "q".repeat(201)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[0].field").value("environment"))
                .andExpect(jsonPath("$.errors[0].message").value("size must be between 0 and 50"))
                .andExpect(jsonPath("$.errors[1].field").value("query"))
                .andExpect(jsonPath("$.errors[1].message").value("size must be between 0 and 200"));

        verifyNoInteractions(issueQueryService);
    }

    @Test
    void rejectsSearchTextWithANullCharacter() throws Exception {
        mockMvc.perform(get(ISSUES_PATH).param("query", "bad\u0000text"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("query"))
                .andExpect(jsonPath("$.errors[0].message").value("must not contain null characters"));

        verifyNoInteractions(issueQueryService);
    }

    @Test
    void reportsAnInvalidDateRangeFromTheService() throws Exception {
        when(issueQueryService.search(any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new InvalidIssueQueryException("lastSeenTo", "must be after lastSeenFrom"));

        mockMvc.perform(get(ISSUES_PATH)
                        .param("lastSeenFrom", "2026-10-02T00:00:00Z")
                        .param("lastSeenTo", "2026-10-01T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.errors[0].field").value("lastSeenTo"))
                .andExpect(jsonPath("$.errors[0].message").value("must be after lastSeenFrom"));
    }

    @Test
    void returnsAnIssueById() throws Exception {
        Issue issue = storedIssue();
        issue.resolve(FIRST_SEEN.plusSeconds(60));
        when(issueQueryService.getIssue(ISSUE_ID)).thenReturn(issue);

        mockMvc.perform(get(ISSUES_PATH + "/" + ISSUE_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(ISSUE_ID.toString()))
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolvedAt").value("2026-10-12T09:01:00Z"));
    }

    @Test
    void returnsNotFoundForAnUnknownIssue() throws Exception {
        when(issueQueryService.getIssue(ISSUE_ID)).thenThrow(new IssueNotFoundException(ISSUE_ID));

        mockMvc.perform(get(ISSUES_PATH + "/" + ISSUE_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Issue not found"))
                .andExpect(jsonPath("$.detail").value("No issue exists with this ID."))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(content().string(not(containsString(ISSUE_ID.toString() + " does not exist"))));
    }

    @Test
    void rejectsAnIssueIdThatIsNotAUuid() throws Exception {
        mockMvc.perform(get(ISSUES_PATH + "/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.errors[0].field").value("id"))
                .andExpect(jsonPath("$.errors[0].message").value("has an invalid value"))
                .andExpect(content().string(not(containsString("Failed to convert"))))
                .andExpect(content().string(not(containsString("java."))));

        verifyNoInteractions(issueQueryService);
    }

    @Test
    void resolvesAnIssue() throws Exception {
        Issue issue = storedIssue();
        issue.resolve(FIRST_SEEN.plusSeconds(90));
        when(issueLifecycleService.resolve(ISSUE_ID)).thenReturn(issue);

        mockMvc.perform(post(ISSUES_PATH + "/" + ISSUE_ID + "/resolve"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(ISSUE_ID.toString()))
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolvedAt").value("2026-10-12T09:01:30Z"));
    }

    @Test
    void reopensAnIssue() throws Exception {
        when(issueLifecycleService.reopen(ISSUE_ID)).thenReturn(storedIssue());

        mockMvc.perform(post(ISSUES_PATH + "/" + ISSUE_ID + "/reopen"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ISSUE_ID.toString()))
                .andExpect(jsonPath("$.status").value("UNRESOLVED"))
                .andExpect(jsonPath("$.resolvedAt").value(nullValue()));
    }

    @ParameterizedTest
    @CsvSource({
            "resolve, RESOLVED",
            "reopen, UNRESOLVED"})
    void returnsConflictWhenTheIssueIsAlreadyInTheRequestedStatus(String action, IssueStatus status)
            throws Exception {
        InvalidIssueStatusTransitionException conflict = new InvalidIssueStatusTransitionException(status, status);
        when(issueLifecycleService.resolve(ISSUE_ID)).thenThrow(conflict);
        when(issueLifecycleService.reopen(ISSUE_ID)).thenThrow(conflict);

        mockMvc.perform(post(ISSUES_PATH + "/" + ISSUE_ID + "/" + action))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid status transition"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail")
                        .value("The issue status cannot change from " + status + " to " + status + "."))
                .andExpect(jsonPath("$.currentStatus").value(status.name()))
                .andExpect(jsonPath("$.requestedStatus").value(status.name()));
    }

    @Test
    void returnsConflictWhenAnotherRequestChangedTheIssueFirst() throws Exception {
        when(issueLifecycleService.resolve(ISSUE_ID))
                .thenThrow(new ObjectOptimisticLockingFailureException(Issue.class, ISSUE_ID));

        mockMvc.perform(post(ISSUES_PATH + "/" + ISSUE_ID + "/resolve"))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Concurrent modification"))
                .andExpect(jsonPath("$.detail")
                        .value("The resource was changed by another request. Fetch it again and retry."))
                .andExpect(content().string(not(containsString("com.tracelite"))))
                .andExpect(content().string(not(containsString("optimistic locking failed"))));
    }

    @ParameterizedTest
    @CsvSource({"resolve", "reopen"})
    void returnsNotFoundWhenChangingAnUnknownIssue(String action) throws Exception {
        when(issueLifecycleService.resolve(ISSUE_ID)).thenThrow(new IssueNotFoundException(ISSUE_ID));
        when(issueLifecycleService.reopen(ISSUE_ID)).thenThrow(new IssueNotFoundException(ISSUE_ID));

        mockMvc.perform(post(ISSUES_PATH + "/" + ISSUE_ID + "/" + action))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Issue not found"));
    }

    @ParameterizedTest
    @CsvSource({"resolve", "reopen"})
    void rejectsALifecycleChangeForAnIdThatIsNotAUuid(String action) throws Exception {
        mockMvc.perform(post(ISSUES_PATH + "/not-a-uuid/" + action))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("id"))
                .andExpect(jsonPath("$.errors[0].message").value("has an invalid value"));

        verifyNoInteractions(issueLifecycleService);
    }

    @Test
    void rejectsGetOnALifecycleEndpoint() throws Exception {
        mockMvc.perform(get(ISSUES_PATH + "/" + ISSUE_ID + "/resolve"))
                .andExpect(status().isMethodNotAllowed());

        verifyNoInteractions(issueLifecycleService);
    }

    private static Issue storedIssue() {
        Issue issue = new Issue(
                "checkout-service",
                "production",
                "ab".repeat(32),
                "java.lang.IllegalStateException",
                "Cart is empty",
                FIRST_SEEN);
        ReflectionTestUtils.setField(issue, "id", ISSUE_ID);
        return issue;
    }
}
