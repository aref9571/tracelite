package com.tracelite.event.api;

import com.tracelite.event.application.ErrorEventSubmission;
import com.tracelite.event.domain.ErrorEvent;
import com.tracelite.issue.domain.Issue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;

public record IngestEventRequest(
        @NotNull
        @Size(max = Issue.MAX_APPLICATION_LENGTH)
        @Pattern(regexp = IDENTIFIER_PATTERN, message = IDENTIFIER_MESSAGE)
        String application,

        @NotNull
        @Size(max = Issue.MAX_ENVIRONMENT_LENGTH)
        @Pattern(regexp = IDENTIFIER_PATTERN, message = IDENTIFIER_MESSAGE)
        String environment,

        @NotBlank
        @Size(max = Issue.MAX_EXCEPTION_TYPE_LENGTH)
        String exceptionType,

        @Size(max = Issue.MAX_MESSAGE_LENGTH)
        String message,

        @Size(max = ErrorEvent.MAX_STACK_TRACE_LENGTH)
        String stackTrace,

        @NotNull
        Instant occurredAt,

        @Size(max = ErrorEvent.MAX_CORRELATION_ID_LENGTH)
        @Pattern(regexp = IDENTIFIER_PATTERN, message = IDENTIFIER_MESSAGE)
        String correlationId,

        @Size(max = MAX_CONTEXT_ENTRIES)
        Map<String, Object> context) {

    public static final int MAX_CONTEXT_ENTRIES = 50;

    static final String IDENTIFIER_PATTERN = "[A-Za-z0-9._:-]+";
    static final String IDENTIFIER_MESSAGE = "must contain only letters, digits, '.', '_', ':' or '-'";

    ErrorEventSubmission toSubmission() {
        return new ErrorEventSubmission(
                application, environment, exceptionType, message, stackTrace, occurredAt, correlationId, context);
    }
}
