package com.tracelite.event;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tracelite.config.IngestionConfiguration;
import com.tracelite.event.api.IssueHistoryController;
import com.tracelite.event.application.IssueHistoryService;
import com.tracelite.event.application.IssueStatistics;
import com.tracelite.event.application.IssueStatistics.DailyEventCount;
import com.tracelite.event.domain.ErrorEvent;
import com.tracelite.issue.application.IssueNotFoundException;
import com.tracelite.issue.domain.Issue;
import com.tracelite.security.SecurityConfiguration;
import com.tracelite.security.TestApiKeys;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(IssueHistoryController.class)
@Import({IngestionConfiguration.class, SecurityConfiguration.class})
class IssueHistoryControllerTests {

    private static final UUID ISSUE_ID = UUID.fromString("5b1f7c9e-3f4a-4d2b-9a61-0c8e2f3d4a5b");
    private static final UUID EVENT_ID = UUID.fromString("0d9c8b7a-6f5e-4d3c-8b2a-1f0e9d8c7b6a");
    private static final String EVENTS_PATH = "/api/v1/issues/" + ISSUE_ID + "/events";
    private static final String STATS_PATH = "/api/v1/issues/" + ISSUE_ID + "/stats";
    private static final String STACK_TRACE =
            "java.lang.IllegalStateException: Cart is empty\n\tat com.example.Cart.checkout(Cart.java:42)";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IssueHistoryService issueHistoryService;

    @Test
    void listsEventsWithTheirStackTracesAndTimestamps() throws Exception {
        when(issueHistoryService.getEvents(ISSUE_ID, 0, 20))
                .thenReturn(new PageImpl<>(List.of(storedEvent()), PageRequest.of(0, 20), 1));

        mockMvc.perform(get(EVENTS_PATH).header(TestApiKeys.HEADER, TestApiKeys.MANAGEMENT_KEY))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(EVENT_ID.toString()))
                .andExpect(jsonPath("$.items[0].exceptionType").value("java.lang.IllegalStateException"))
                .andExpect(jsonPath("$.items[0].message").value("Cart is empty"))
                .andExpect(jsonPath("$.items[0].stackTrace").value(STACK_TRACE))
                .andExpect(jsonPath("$.items[0].occurredAt").value("2026-10-13T09:59:58Z"))
                .andExpect(jsonPath("$.items[0].receivedAt").value("2026-10-13T10:00:00.123456Z"))
                .andExpect(jsonPath("$.items[0].correlationId").value("req-7f3a"))
                .andExpect(jsonPath("$.items[0].context.region").value("eu-west-1"))
                .andExpect(jsonPath("$.items[0].context.attempt").value(3))
                .andExpect(jsonPath("$.items[0].issue").doesNotExist())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void passesThePageAndSizeToTheService() throws Exception {
        when(issueHistoryService.getEvents(ISSUE_ID, 3, 5))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(3, 5), 12));

        mockMvc.perform(get(EVENTS_PATH).header(TestApiKeys.HEADER, TestApiKeys.MANAGEMENT_KEY).param("page", "3").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.page").value(3))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalItems").value(12))
                .andExpect(jsonPath("$.totalPages").value(3));

        verify(issueHistoryService).getEvents(ISSUE_ID, 3, 5);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            page | -1    | must be greater than or equal to 0
            page | 10001 | must be less than or equal to 10000
            page | first | has an invalid value
            size | 0     | must be greater than or equal to 1
            size | 101   | must be less than or equal to 100
            size | many  | has an invalid value
            """)
    void rejectsAnInvalidPageParameter(String parameter, String value, String message) throws Exception {
        mockMvc.perform(get(EVENTS_PATH).header(TestApiKeys.HEADER, TestApiKeys.MANAGEMENT_KEY).param(parameter, value))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.errors[0].field").value(parameter))
                .andExpect(jsonPath("$.errors[0].message").value(message))
                .andExpect(content().string(not(containsString("java."))));

        verifyNoInteractions(issueHistoryService);
    }

    @Test
    void returnsNotFoundForTheEventsOfAnUnknownIssue() throws Exception {
        when(issueHistoryService.getEvents(any(), anyInt(), anyInt())).thenThrow(new IssueNotFoundException(ISSUE_ID));

        mockMvc.perform(get(EVENTS_PATH).header(TestApiKeys.HEADER, TestApiKeys.MANAGEMENT_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Issue not found"));
    }

    @ParameterizedTest
    @CsvSource({"events", "stats"})
    void rejectsAnIssueIdThatIsNotAUuid(String resource) throws Exception {
        mockMvc.perform(get("/api/v1/issues/not-a-uuid/" + resource).header(TestApiKeys.HEADER, TestApiKeys.MANAGEMENT_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("id"))
                .andExpect(jsonPath("$.errors[0].message").value("has an invalid value"));

        verifyNoInteractions(issueHistoryService);
    }

    @Test
    void returnsIssueStatistics() throws Exception {
        when(issueHistoryService.getStatistics(ISSUE_ID)).thenReturn(new IssueStatistics(
                ISSUE_ID,
                5,
                5,
                Instant.parse("2026-10-01T08:00:00Z"),
                Instant.parse("2026-10-13T10:00:00Z"),
                Instant.parse("2026-10-13T10:30:00Z"),
                2,
                4,
                List.of(
                        new DailyEventCount(LocalDate.parse("2026-10-12"), 0),
                        new DailyEventCount(LocalDate.parse("2026-10-13"), 2))));

        mockMvc.perform(get(STATS_PATH).header(TestApiKeys.HEADER, TestApiKeys.MANAGEMENT_KEY))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.issueId").value(ISSUE_ID.toString()))
                .andExpect(jsonPath("$.occurrenceCount").value(5))
                .andExpect(jsonPath("$.storedEventCount").value(5))
                .andExpect(jsonPath("$.firstSeenAt").value("2026-10-01T08:00:00Z"))
                .andExpect(jsonPath("$.lastSeenAt").value("2026-10-13T10:00:00Z"))
                .andExpect(jsonPath("$.calculatedAt").value("2026-10-13T10:30:00Z"))
                .andExpect(jsonPath("$.eventsLast24Hours").value(2))
                .andExpect(jsonPath("$.eventsLast7Days").value(4))
                .andExpect(jsonPath("$.dailyEventCounts.length()").value(2))
                .andExpect(jsonPath("$.dailyEventCounts[0].date").value("2026-10-12"))
                .andExpect(jsonPath("$.dailyEventCounts[0].count").value(0))
                .andExpect(jsonPath("$.dailyEventCounts[1].date").value("2026-10-13"))
                .andExpect(jsonPath("$.dailyEventCounts[1].count").value(2));
    }

    @Test
    void returnsNotFoundForTheStatisticsOfAnUnknownIssue() throws Exception {
        when(issueHistoryService.getStatistics(ISSUE_ID)).thenThrow(new IssueNotFoundException(ISSUE_ID));

        mockMvc.perform(get(STATS_PATH).header(TestApiKeys.HEADER, TestApiKeys.MANAGEMENT_KEY))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Issue not found"))
                .andExpect(jsonPath("$.issueId").doesNotExist());
    }

    private static ErrorEvent storedEvent() {
        Issue issue = new Issue(
                "checkout-service",
                "production",
                "ab".repeat(32),
                "java.lang.IllegalStateException",
                "Cart is empty",
                Instant.parse("2026-10-13T10:00:00Z"));
        ErrorEvent event = new ErrorEvent(
                issue,
                "java.lang.IllegalStateException",
                "Cart is empty",
                STACK_TRACE,
                Instant.parse("2026-10-13T09:59:58Z"),
                Instant.parse("2026-10-13T10:00:00.123456Z"),
                "req-7f3a",
                Map.of("region", "eu-west-1", "attempt", 3));
        ReflectionTestUtils.setField(event, "id", EVENT_ID);
        return event;
    }
}
