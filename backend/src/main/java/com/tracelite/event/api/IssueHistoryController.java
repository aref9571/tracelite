package com.tracelite.event.api;

import com.tracelite.common.web.PageResponse;
import com.tracelite.event.application.IssueHistoryService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/issues/{id}")
public class IssueHistoryController {

    private final IssueHistoryService issueHistoryService;

    public IssueHistoryController(IssueHistoryService issueHistoryService) {
        this.issueHistoryService = issueHistoryService;
    }

    @GetMapping("/events")
    public PageResponse<EventResponse> events(
            @PathVariable UUID id, @Valid @ParameterObject @ModelAttribute EventPageRequest request) {
        return PageResponse.from(
                issueHistoryService.getEvents(id, request.pageOrDefault(), request.sizeOrDefault()),
                EventResponse::from);
    }

    @GetMapping("/stats")
    public IssueStatisticsResponse stats(@PathVariable UUID id) {
        return IssueStatisticsResponse.from(issueHistoryService.getStatistics(id));
    }
}
