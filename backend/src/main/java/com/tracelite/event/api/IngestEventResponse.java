package com.tracelite.event.api;

import com.tracelite.event.domain.ErrorEvent;
import java.time.Instant;
import java.util.UUID;

public record IngestEventResponse(UUID eventId, UUID issueId, Instant receivedAt) {

    static IngestEventResponse from(ErrorEvent event) {
        return new IngestEventResponse(event.getId(), event.getIssue().getId(), event.getReceivedAt());
    }
}
