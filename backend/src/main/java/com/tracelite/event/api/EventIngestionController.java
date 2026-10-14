package com.tracelite.event.api;

import com.tracelite.event.application.EventIngestionService;
import com.tracelite.event.domain.ErrorEvent;
import com.tracelite.security.ApplicationAccessDeniedException;
import com.tracelite.security.IngestionClient;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/events")
public class EventIngestionController {

    private final EventIngestionService eventIngestionService;

    public EventIngestionController(EventIngestionService eventIngestionService) {
        this.eventIngestionService = eventIngestionService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public IngestEventResponse ingest(
            @Valid @RequestBody IngestEventRequest request, @AuthenticationPrincipal IngestionClient client) {
        if (!client.mayIngestFor(request.application())) {
            throw new ApplicationAccessDeniedException();
        }
        ErrorEvent event = eventIngestionService.ingest(request.toSubmission());
        return IngestEventResponse.from(event);
    }
}
