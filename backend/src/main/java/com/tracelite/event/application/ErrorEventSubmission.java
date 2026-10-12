package com.tracelite.event.application;

import java.time.Instant;
import java.util.Map;

public record ErrorEventSubmission(
        String application,
        String environment,
        String exceptionType,
        String message,
        String stackTrace,
        Instant occurredAt,
        String correlationId,
        Map<String, Object> context) {
}
