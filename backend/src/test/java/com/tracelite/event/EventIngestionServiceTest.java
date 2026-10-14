package com.tracelite.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tracelite.config.IngestionProperties;
import com.tracelite.event.application.ErrorEventSubmission;
import com.tracelite.event.application.EventIngestionService;
import com.tracelite.event.application.InvalidErrorEventException;
import com.tracelite.event.domain.ErrorEvent;
import com.tracelite.event.persistence.ErrorEventRepository;
import com.tracelite.issue.application.FingerprintGenerator;
import com.tracelite.issue.domain.Issue;
import com.tracelite.issue.persistence.IssueRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.unit.DataSize;

class EventIngestionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-12T09:00:00Z");
    private static final String EXCEPTION_TYPE = "java.lang.IllegalStateException";
    private static final String STACK_TRACE = "at com.example.Cart.checkout(Cart.java:42)";
    private static final String FINGERPRINT = new FingerprintGenerator().generate(EXCEPTION_TYPE, STACK_TRACE);
    private static final UUID ISSUE_ID = UUID.randomUUID();

    private final IssueRepository issueRepository = mock(IssueRepository.class);
    private final ErrorEventRepository errorEventRepository = mock(ErrorEventRepository.class);
    private final IngestionProperties properties =
            new IngestionProperties(DataSize.ofKilobytes(256), Duration.ofMinutes(5), Duration.ofDays(30));

    private final Issue issue = new Issue("checkout-service", "production", FINGERPRINT, EXCEPTION_TYPE,
            "Cart is empty", NOW.minus(Duration.ofHours(1)));

    private EventIngestionService service;

    @BeforeEach
    void setUp() {
        service = new EventIngestionService(
                issueRepository,
                errorEventRepository,
                new FingerprintGenerator(),
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
        ReflectionTestUtils.setField(issue, "id", ISSUE_ID);
        when(issueRepository.recordOccurrence(any(), any(), any(), any(), any(), any())).thenReturn(ISSUE_ID);
        when(issueRepository.getReferenceById(ISSUE_ID)).thenReturn(issue);
        when(errorEventRepository.save(any(ErrorEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void recordsTheOccurrenceOnTheIssueForTheEventsFingerprint() {
        ErrorEvent event = service.ingest(submission(NOW.minusSeconds(30)));

        verify(issueRepository).recordOccurrence(
                "checkout-service", "production", FINGERPRINT, EXCEPTION_TYPE, "Cart is empty", NOW);
        verify(issueRepository).getReferenceById(ISSUE_ID);
        assertThat(event.getIssue()).isSameAs(issue);
    }

    @Test
    void fingerprintsTheStackTrace() {
        ErrorEventSubmission withOtherTrace = new ErrorEventSubmission("checkout-service", "production",
                EXCEPTION_TYPE, "Cart is empty", "at com.example.Cart.addItem(Cart.java:42)", NOW, null, null);

        service.ingest(withOtherTrace);

        String otherFingerprint = new FingerprintGenerator().generate(EXCEPTION_TYPE, withOtherTrace.stackTrace());
        assertThat(otherFingerprint).isNotEqualTo(FINGERPRINT);
        verify(issueRepository).recordOccurrence(
                "checkout-service", "production", otherFingerprint, EXCEPTION_TYPE, "Cart is empty", NOW);
    }

    @Test
    void storesTheEventWithServerReceivedTime() {
        Instant occurredAt = NOW.minusSeconds(30);

        service.ingest(submission(occurredAt));

        ArgumentCaptor<ErrorEvent> eventCaptor = ArgumentCaptor.forClass(ErrorEvent.class);
        verify(errorEventRepository).save(eventCaptor.capture());
        ErrorEvent event = eventCaptor.getValue();
        assertThat(event.getExceptionType()).isEqualTo(EXCEPTION_TYPE);
        assertThat(event.getMessage()).isEqualTo("Cart is empty");
        assertThat(event.getStackTrace()).isEqualTo("at com.example.Cart.checkout(Cart.java:42)");
        assertThat(event.getOccurredAt()).isEqualTo(occurredAt);
        assertThat(event.getReceivedAt()).isEqualTo(NOW);
        assertThat(event.getCorrelationId()).isEqualTo("req-123");
        assertThat(event.getContext()).containsEntry("orderId", "o-1");
    }

    @Test
    void truncatesTimestampsToTheMicrosecondsPostgresStores() {
        service = new EventIngestionService(issueRepository, errorEventRepository, new FingerprintGenerator(),
                properties, Clock.fixed(NOW.plusNanos(123_456_789), ZoneOffset.UTC));

        ErrorEvent event = service.ingest(submission(NOW.minusSeconds(30).plusNanos(987_654_321)));

        assertThat(event.getReceivedAt()).isEqualTo(NOW.plusNanos(123_456_000));
        assertThat(event.getOccurredAt()).isEqualTo(NOW.minusSeconds(30).plusNanos(987_654_000));
    }

    @Test
    void acceptsOccurredAtAtTheEdgesOfTheAllowedRange() {
        service.ingest(submission(NOW.plus(Duration.ofMinutes(5))));
        service.ingest(submission(NOW.minus(Duration.ofDays(30))));

        verify(errorEventRepository, times(2)).save(any());
    }

    @Test
    void rejectsOccurredAtTooFarInTheFuture() {
        assertThatThrownBy(() -> service.ingest(submission(NOW.plus(Duration.ofMinutes(5)).plusSeconds(1))))
                .isInstanceOfSatisfying(InvalidErrorEventException.class, exception -> {
                    assertThat(exception.getField()).isEqualTo("occurredAt");
                    assertThat(exception.getReason()).isEqualTo("must not be after 2026-10-12T09:05:00Z");
                });
        verifyNoInteractions(issueRepository, errorEventRepository);
    }

    @Test
    void rejectsOccurredAtOlderThanTheMaximumEventAge() {
        assertThatThrownBy(() -> service.ingest(submission(NOW.minus(Duration.ofDays(30)).minusSeconds(1))))
                .isInstanceOfSatisfying(InvalidErrorEventException.class, exception -> {
                    assertThat(exception.getField()).isEqualTo("occurredAt");
                    assertThat(exception.getReason()).isEqualTo("must not be before 2026-09-12T09:00:00Z");
                });
        verifyNoInteractions(issueRepository, errorEventRepository);
    }

    @Test
    void rejectsNullCharactersInTextFields() {
        ErrorEventSubmission submission = new ErrorEventSubmission("checkout-service", "production", EXCEPTION_TYPE,
                "Cart\u0000is empty", null, NOW, null, null);

        assertThatThrownBy(() -> service.ingest(submission))
                .isInstanceOfSatisfying(InvalidErrorEventException.class,
                        exception -> assertThat(exception.getField()).isEqualTo("message"));
        verifyNoInteractions(issueRepository, errorEventRepository);
    }

    @Test
    void rejectsNullCharactersNestedInContext() {
        ErrorEventSubmission submission = new ErrorEventSubmission("checkout-service", "production", EXCEPTION_TYPE,
                null, null, NOW, null, Map.of("items", List.of(Map.of("sku", "a\u0000b"))));

        assertThatThrownBy(() -> service.ingest(submission))
                .isInstanceOfSatisfying(InvalidErrorEventException.class,
                        exception -> assertThat(exception.getField()).isEqualTo("context"));
        verifyNoInteractions(issueRepository, errorEventRepository);
    }

    @Test
    void acceptsNestedListsAndMapsInContextWithoutNullCharacters() {
        Map<String, Object> context = Map.of("items", List.of(Map.of("sku", "a-1"), "gift-card", 3));
        ErrorEventSubmission submission = new ErrorEventSubmission("checkout-service", "production", EXCEPTION_TYPE,
                null, null, NOW, null, context);

        ErrorEvent event = service.ingest(submission);

        assertThat(event.getContext()).isEqualTo(context);
        verify(errorEventRepository).save(any(ErrorEvent.class));
    }

    private static ErrorEventSubmission submission(Instant occurredAt) {
        return new ErrorEventSubmission(
                "checkout-service",
                "production",
                EXCEPTION_TYPE,
                "Cart is empty",
                STACK_TRACE,
                occurredAt,
                "req-123",
                Map.of("orderId", "o-1"));
    }
}
