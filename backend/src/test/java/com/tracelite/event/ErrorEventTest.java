package com.tracelite.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tracelite.event.domain.ErrorEvent;
import com.tracelite.issue.domain.Issue;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ErrorEventTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-10-11T09:00:00Z");
    private static final Instant RECEIVED_AT = Instant.parse("2026-10-11T09:00:01Z");

    private final Issue issue = new Issue(
            "checkout-service", "production", "f".repeat(64), "java.lang.IllegalStateException", "Cart is empty", OCCURRED_AT);

    @Test
    void contextIsCopiedAndCannotBeModified() {
        Map<String, Object> context = new HashMap<>();
        context.put("orderId", "A-1001");
        context.put("retry", null);

        ErrorEvent event = newEvent("trace-1", context);
        context.put("orderId", "changed");

        assertThat(event.getContext()).containsEntry("orderId", "A-1001").containsEntry("retry", null);
        assertThatThrownBy(() -> event.getContext().put("other", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void optionalFieldsMayBeAbsent() {
        ErrorEvent event = new ErrorEvent(issue, "java.lang.IllegalStateException", null, null, OCCURRED_AT, RECEIVED_AT, null, null);

        assertThat(event.getMessage()).isNull();
        assertThat(event.getStackTrace()).isNull();
        assertThat(event.getCorrelationId()).isNull();
        assertThat(event.getContext()).isNull();
    }

    @Test
    void issueAndTimestampsAreRequired() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ErrorEvent(null, "java.lang.IllegalStateException", null, null, OCCURRED_AT, RECEIVED_AT, null, null));
        assertThatNullPointerException()
                .isThrownBy(() -> new ErrorEvent(issue, "java.lang.IllegalStateException", null, null, null, RECEIVED_AT, null, null));
        assertThatNullPointerException()
                .isThrownBy(() -> new ErrorEvent(issue, "java.lang.IllegalStateException", null, null, OCCURRED_AT, null, null, null));
    }

    @Test
    void exceptionTypeIsRequired() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ErrorEvent(issue, " ", null, null, OCCURRED_AT, RECEIVED_AT, null, null))
                .withMessage("exceptionType must not be blank");
    }

    @Test
    void stackTraceAndCorrelationIdLengthsAreLimited() {
        String stackTrace = "s".repeat(ErrorEvent.MAX_STACK_TRACE_LENGTH + 1);
        String correlationId = "c".repeat(ErrorEvent.MAX_CORRELATION_ID_LENGTH + 1);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ErrorEvent(issue, "java.lang.IllegalStateException", null, stackTrace, OCCURRED_AT, RECEIVED_AT, null, null))
                .withMessage("stackTrace must be at most 65536 characters");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> newEvent(correlationId, null))
                .withMessage("correlationId must be at most 128 characters");
    }

    private ErrorEvent newEvent(String correlationId, Map<String, Object> context) {
        return new ErrorEvent(issue, "java.lang.IllegalStateException", "Cart is empty",
                "java.lang.IllegalStateException: Cart is empty\n\tat com.example.Cart.checkout(Cart.java:42)",
                OCCURRED_AT, RECEIVED_AT, correlationId, context);
    }
}
