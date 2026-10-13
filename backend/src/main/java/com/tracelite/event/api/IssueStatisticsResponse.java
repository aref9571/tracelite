package com.tracelite.event.api;

import com.tracelite.event.application.IssueStatistics;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record IssueStatisticsResponse(
        UUID issueId,
        long occurrenceCount,
        long storedEventCount,
        Instant firstSeenAt,
        Instant lastSeenAt,
        Instant calculatedAt,
        long eventsLast24Hours,
        long eventsLast7Days,
        List<DailyEventCountResponse> dailyEventCounts) {

    public record DailyEventCountResponse(LocalDate date, long count) {
    }

    static IssueStatisticsResponse from(IssueStatistics statistics) {
        return new IssueStatisticsResponse(
                statistics.issueId(),
                statistics.occurrenceCount(),
                statistics.storedEventCount(),
                statistics.firstSeenAt(),
                statistics.lastSeenAt(),
                statistics.calculatedAt(),
                statistics.eventsLast24Hours(),
                statistics.eventsLast7Days(),
                statistics.dailyEventCounts().stream()
                        .map(daily -> new DailyEventCountResponse(daily.date(), daily.count()))
                        .toList());
    }
}
