package com.tracelite.event.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record IssueStatistics(
        UUID issueId,
        long occurrenceCount,
        long storedEventCount,
        Instant firstSeenAt,
        Instant lastSeenAt,
        Instant calculatedAt,
        long eventsLast24Hours,
        long eventsLast7Days,
        List<DailyEventCount> dailyEventCounts) {

    public record DailyEventCount(LocalDate date, long count) {
    }
}
