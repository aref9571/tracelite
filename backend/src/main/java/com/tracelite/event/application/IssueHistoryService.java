package com.tracelite.event.application;

import com.tracelite.event.application.IssueStatistics.DailyEventCount;
import com.tracelite.event.domain.ErrorEvent;
import com.tracelite.event.persistence.DailyEventCountView;
import com.tracelite.event.persistence.ErrorEventRepository;
import com.tracelite.event.persistence.EventCountsView;
import com.tracelite.issue.application.IssueNotFoundException;
import com.tracelite.issue.domain.Issue;
import com.tracelite.issue.persistence.IssueRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IssueHistoryService {

    public static final int DAILY_COUNT_DAYS = 14;

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "receivedAt")
            .and(Sort.by(Sort.Direction.DESC, "id"));

    private final IssueRepository issueRepository;
    private final ErrorEventRepository errorEventRepository;
    private final Clock clock;

    public IssueHistoryService(
            IssueRepository issueRepository, ErrorEventRepository errorEventRepository, Clock clock) {
        this.issueRepository = issueRepository;
        this.errorEventRepository = errorEventRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<ErrorEvent> getEvents(UUID issueId, int page, int size) {
        Page<ErrorEvent> events = errorEventRepository.findByIssueId(issueId, PageRequest.of(page, size, NEWEST_FIRST));
        if (events.isEmpty() && !issueRepository.existsById(issueId)) {
            throw new IssueNotFoundException(issueId);
        }
        return events;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public IssueStatistics getStatistics(UUID issueId) {
        Issue issue = issueRepository.findById(issueId).orElseThrow(() -> new IssueNotFoundException(issueId));
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        LocalDate firstDay = today.minusDays(DAILY_COUNT_DAYS - 1);
        EventCountsView counts = errorEventRepository.countEvents(
                issueId, now.minus(Duration.ofHours(24)), now.minus(Duration.ofDays(7)));

        return new IssueStatistics(
                issue.getId(),
                issue.getOccurrenceCount(),
                counts.getTotalEvents(),
                issue.getFirstSeenAt(),
                issue.getLastSeenAt(),
                now,
                counts.getEventsSinceLastDay(),
                counts.getEventsSinceLastWeek(),
                dailyEventCounts(issueId, firstDay, today));
    }

    private List<DailyEventCount> dailyEventCounts(UUID issueId, LocalDate firstDay, LocalDate lastDay) {
        Instant since = firstDay.atStartOfDay(ZoneOffset.UTC).toInstant();
        Map<LocalDate, Long> countsByDay = errorEventRepository.countPerUtcDay(issueId, since).stream()
                .collect(Collectors.toMap(
                        view -> LocalDate.parse(view.getDay()), DailyEventCountView::getEventCount));

        List<DailyEventCount> counts = new ArrayList<>();
        for (LocalDate day = firstDay; !day.isAfter(lastDay); day = day.plusDays(1)) {
            counts.add(new DailyEventCount(day, countsByDay.getOrDefault(day, 0L)));
        }
        return counts;
    }
}
