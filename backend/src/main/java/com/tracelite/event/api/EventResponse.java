package com.tracelite.event.api;

import com.tracelite.event.domain.ErrorEvent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record EventResponse(
        UUID id,
        String exceptionType,
        String message,
        String stackTrace,
        Instant occurredAt,
        Instant receivedAt,
        String correlationId,
        Map<String, Object> context) {

    static EventResponse from(ErrorEvent event) {
        return new EventResponse(
                event.getId(),
                event.getExceptionType(),
                event.getMessage(),
                event.getStackTrace(),
                event.getOccurredAt(),
                event.getReceivedAt(),
                event.getCorrelationId(),
                event.getContext());
    }
}
