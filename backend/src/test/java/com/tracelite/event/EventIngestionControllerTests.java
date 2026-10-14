package com.tracelite.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tracelite.config.IngestionConfiguration;
import com.tracelite.event.api.EventIngestionController;
import com.tracelite.event.application.ErrorEventSubmission;
import com.tracelite.event.application.EventIngestionService;
import com.tracelite.event.application.InvalidErrorEventException;
import com.tracelite.event.domain.ErrorEvent;
import com.tracelite.issue.domain.Issue;
import com.tracelite.security.SecurityConfiguration;
import com.tracelite.security.TestApiKeys;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.CannotCreateTransactionException;

@WebMvcTest(EventIngestionController.class)
@Import({IngestionConfiguration.class, SecurityConfiguration.class})
class EventIngestionControllerTests {

    private static final String EVENTS_PATH = "/api/v1/events";
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-12T08:59:30Z");
    private static final Instant RECEIVED_AT = Instant.parse("2026-10-12T09:00:00Z");

    private static final String VALID_EVENT = """
            {
              "application": "checkout-service",
              "environment": "production",
              "exceptionType": "java.lang.IllegalStateException",
              "message": "Cart is empty",
              "stackTrace": "java.lang.IllegalStateException: Cart is empty\\n\\tat com.example.Cart.checkout(Cart.java:42)",
              "occurredAt": "2026-10-12T08:59:30Z",
              "correlationId": "req-7f3a",
              "context": {"orderId": "o-1001", "retry": 2}
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EventIngestionService eventIngestionService;

    @Test
    void acceptsAValidEventAndReturnsCreated() throws Exception {
        ErrorEvent stored = storedEvent();
        when(eventIngestionService.ingest(any())).thenReturn(stored);

        mockMvc.perform(post(EVENTS_PATH).header(TestApiKeys.HEADER, TestApiKeys.CHECKOUT_INGESTION_KEY).contentType(MediaType.APPLICATION_JSON).content(VALID_EVENT))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.eventId").value(stored.getId().toString()))
                .andExpect(jsonPath("$.issueId").value(stored.getIssue().getId().toString()))
                .andExpect(jsonPath("$.receivedAt").value("2026-10-12T09:00:00Z"));

        ArgumentCaptor<ErrorEventSubmission> captor = ArgumentCaptor.forClass(ErrorEventSubmission.class);
        verify(eventIngestionService).ingest(captor.capture());
        ErrorEventSubmission submission = captor.getValue();
        assertThat(submission.application()).isEqualTo("checkout-service");
        assertThat(submission.environment()).isEqualTo("production");
        assertThat(submission.exceptionType()).isEqualTo("java.lang.IllegalStateException");
        assertThat(submission.message()).isEqualTo("Cart is empty");
        assertThat(submission.stackTrace()).contains("Cart.java:42");
        assertThat(submission.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(submission.correlationId()).isEqualTo("req-7f3a");
        assertThat(submission.context()).containsEntry("orderId", "o-1001").containsEntry("retry", 2);
    }

    @Test
    void rejectsAnEventForAnotherApplicationWithForbidden() throws Exception {
        mockMvc.perform(post(EVENTS_PATH).header(TestApiKeys.HEADER, TestApiKeys.BILLING_INGESTION_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_EVENT))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Forbidden"))
                .andExpect(jsonPath("$.detail").value("The API key is not valid for this application."));

        verifyNoInteractions(eventIngestionService);
    }

    @Test
    void acceptsAnEventWithOnlyRequiredFields() throws Exception {
        when(eventIngestionService.ingest(any())).thenReturn(storedEvent());

        mockMvc.perform(post(EVENTS_PATH).header(TestApiKeys.HEADER, TestApiKeys.CHECKOUT_INGESTION_KEY).contentType(MediaType.APPLICATION_JSON).content("""
                        {"application": "checkout-service", "environment": "production",
                         "exceptionType": "TypeError", "occurredAt": "2026-10-12T11:59:30+03:00"}
                        """))
                .andExpect(status().isCreated());

        ArgumentCaptor<ErrorEventSubmission> captor = ArgumentCaptor.forClass(ErrorEventSubmission.class);
        verify(eventIngestionService).ingest(captor.capture());
        assertThat(captor.getValue().occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(captor.getValue().message()).isNull();
        assertThat(captor.getValue().context()).isNull();
    }

    @Test
    void rejectsAnEventWithoutRequiredFields() throws Exception {
        mockMvc.perform(post(EVENTS_PATH).header(TestApiKeys.HEADER, TestApiKeys.CHECKOUT_INGESTION_KEY).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.detail").value("The request contains invalid fields."))
                .andExpect(jsonPath("$.instance").value(EVENTS_PATH))
                .andExpect(jsonPath("$.errors.length()").value(4))
                .andExpect(jsonPath("$.errors[0].field").value("application"))
                .andExpect(jsonPath("$.errors[0].message").value("must not be null"))
                .andExpect(jsonPath("$.errors[1].field").value("environment"))
                .andExpect(jsonPath("$.errors[2].field").value("exceptionType"))
                .andExpect(jsonPath("$.errors[2].message").value("must not be blank"))
                .andExpect(jsonPath("$.errors[3].field").value("occurredAt"));

        verifyNoInteractions(eventIngestionService);
    }

    @ParameterizedTest
    @CsvSource({
            "application, 101",
            "environment, 51",
            "exceptionType, 256",
            "message, 4001",
            "stackTrace, 65537",
            "correlationId, 129"})
    void rejectsFieldsLongerThanTheDatabaseAllows(String field, int length) throws Exception {
        String body = VALID_EVENT.replaceFirst("\"" + field + "\": \"[^\"]*\"", "\"" + field + "\": \"" + "a".repeat(length) + "\"");

        mockMvc.perform(post(EVENTS_PATH).header(TestApiKeys.HEADER, TestApiKeys.CHECKOUT_INGESTION_KEY).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value(field))
                .andExpect(jsonPath("$.errors[0].message").value(containsString("size must be between")));

        verifyNoInteractions(eventIngestionService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "checkout service", "checkout/service", "prod\\nINFO fake log line"})
    void rejectsApplicationNamesThatAreNotIdentifiers(String application) throws Exception {
        String body = VALID_EVENT.replace("\"checkout-service\"", "\"" + application + "\"");

        mockMvc.perform(post(EVENTS_PATH).header(TestApiKeys.HEADER, TestApiKeys.CHECKOUT_INGESTION_KEY).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("application"))
                .andExpect(jsonPath("$.errors[0].message").value(containsString("must contain only letters")));

        verifyNoInteractions(eventIngestionService);
    }

    @Test
    void rejectsContextWithTooManyEntries() throws Exception {
        StringBuilder context = new StringBuilder("{");
        for (int i = 0; i <= 50; i++) {
            context.append(i == 0 ? "" : ",").append("\"key").append(i).append("\": ").append(i);
        }
        context.append("}");
        String body = VALID_EVENT.replace("{\"orderId\": \"o-1001\", \"retry\": 2}", context);

        mockMvc.perform(post(EVENTS_PATH).header(TestApiKeys.HEADER, TestApiKeys.CHECKOUT_INGESTION_KEY).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("context"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"application\": ",
            "not json",
            "[]",
            "{\"application\": \"a\", \"environment\": \"b\", \"exceptionType\": \"c\", \"occurredAt\": \"yesterday\"}",
            "{\"application\": \"a\", \"environment\": \"b\", \"exceptionType\": \"c\", \"occurredAt\": \"2026-10-12T08:59:30Z\", \"context\": [1, 2]}"})
    void rejectsMalformedJsonWithoutLeakingParserDetails(String body) throws Exception {
        mockMvc.perform(post(EVENTS_PATH).header(TestApiKeys.HEADER, TestApiKeys.CHECKOUT_INGESTION_KEY).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Malformed request"))
                .andExpect(jsonPath("$.instance").value(EVENTS_PATH))
                .andExpect(content().string(not(containsString("Jackson"))))
                .andExpect(content().string(not(containsString("com.tracelite"))))
                .andExpect(content().string(not(containsString("line:"))));

        verifyNoInteractions(eventIngestionService);
    }

    @Test
    void rejectsAnEmptyBody() throws Exception {
        mockMvc.perform(post(EVENTS_PATH).header(TestApiKeys.HEADER, TestApiKeys.CHECKOUT_INGESTION_KEY).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Malformed request"));
    }

    @Test
    void rejectsNonJsonContentTypes() throws Exception {
        mockMvc.perform(post(EVENTS_PATH).header(TestApiKeys.HEADER, TestApiKeys.CHECKOUT_INGESTION_KEY).contentType(MediaType.TEXT_PLAIN).content(VALID_EVENT))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415));
    }

    @Test
    void rejectsOtherHttpMethods() throws Exception {
        mockMvc.perform(get(EVENTS_PATH).header(TestApiKeys.HEADER, TestApiKeys.CHECKOUT_INGESTION_KEY))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    void rejectsABodyWhoseContentLengthExceedsTheLimit() throws Exception {
        String body = VALID_EVENT.replace("Cart is empty\"", "Cart is empty" + " ".repeat(256 * 1024) + "\"");

        mockMvc.perform(post(EVENTS_PATH).header(TestApiKeys.HEADER, TestApiKeys.CHECKOUT_INGESTION_KEY).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Request too large"))
                .andExpect(jsonPath("$.detail").value("The request body must not exceed 262144 bytes."));

        verifyNoInteractions(eventIngestionService);
    }

    @Test
    void mapsServiceValidationFailuresToFieldErrors() throws Exception {
        when(eventIngestionService.ingest(any()))
                .thenThrow(new InvalidErrorEventException("occurredAt", "must not be after 2026-10-12T09:05:00Z"));

        mockMvc.perform(post(EVENTS_PATH).header(TestApiKeys.HEADER, TestApiKeys.CHECKOUT_INGESTION_KEY).contentType(MediaType.APPLICATION_JSON).content(VALID_EVENT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.instance").value(EVENTS_PATH))
                .andExpect(jsonPath("$.errors[0].field").value("occurredAt"))
                .andExpect(jsonPath("$.errors[0].message").value("must not be after 2026-10-12T09:05:00Z"));
    }

    @Test
    void reportsAnUnavailableDatabaseAsServiceUnavailable() throws Exception {
        when(eventIngestionService.ingest(any()))
                .thenThrow(new CannotCreateTransactionException("Connection to db.internal:5432 refused"));

        mockMvc.perform(post(EVENTS_PATH).header(TestApiKeys.HEADER, TestApiKeys.CHECKOUT_INGESTION_KEY).contentType(MediaType.APPLICATION_JSON).content(VALID_EVENT))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Service unavailable"))
                .andExpect(content().string(not(containsString("db.internal"))));
    }

    @Test
    void reportsOtherDatabaseFailuresAsInternalErrorsWithoutDetails() throws Exception {
        when(eventIngestionService.ingest(any()))
                .thenThrow(new DataIntegrityViolationException("violates constraint events_issue_fk"));

        mockMvc.perform(post(EVENTS_PATH).header(TestApiKeys.HEADER, TestApiKeys.CHECKOUT_INGESTION_KEY).contentType(MediaType.APPLICATION_JSON).content(VALID_EVENT))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.title").value("Internal server error"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.instance").value(EVENTS_PATH))
                .andExpect(content().string(not(containsString("events_issue_fk"))))
                .andExpect(content().string(not(containsString("DataIntegrityViolationException"))));
    }

    private static ErrorEvent storedEvent() {
        Issue issue = new Issue("checkout-service", "production", "ab".repeat(32), "java.lang.IllegalStateException",
                "Cart is empty", RECEIVED_AT);
        ReflectionTestUtils.setField(issue, "id", UUID.fromString("5b1f7c9e-3f4a-4d2b-9a61-0c8e2f3d4a5b"));
        ErrorEvent event = new ErrorEvent(issue, "java.lang.IllegalStateException", "Cart is empty", null,
                OCCURRED_AT, RECEIVED_AT, null, Map.of());
        ReflectionTestUtils.setField(event, "id", UUID.fromString("9d2c4e6a-1b3f-4a5c-8e7d-2f4a6b8c0d1e"));
        return event;
    }
}
