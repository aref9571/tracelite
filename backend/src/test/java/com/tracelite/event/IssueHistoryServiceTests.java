package com.tracelite.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tracelite.event.application.IssueHistoryService;
import com.tracelite.event.application.IssueStatistics;
import com.tracelite.event.application.IssueStatistics.DailyEventCount;
import com.tracelite.event.domain.ErrorEvent;
import com.tracelite.event.persistence.ErrorEventRepository;
import com.tracelite.integration.PostgresTestcontainersConfiguration;
import com.tracelite.issue.application.IssueNotFoundException;
import com.tracelite.issue.domain.Issue;
import com.tracelite.issue.persistence.IssueRepository;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        PostgresTestcontainersConfiguration.class,
        IssueHistoryService.class,
        IssueHistoryServiceTests.FixedClockConfiguration.class})
class IssueHistoryServiceTests {

    private static final Instant NOW = Instant.parse("2026-10-13T10:30:00Z");
    private static final Duration ONE_MICROSECOND = Duration.ofNanos(1_000);
    private static final String FINGERPRINT = "c0ffee00".repeat(8);

    @Autowired
    private IssueHistoryService service;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private ErrorEventRepository eventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Test
    void returnsEventsNewestFirstWithTiesOrderedByDescendingId() {
        Issue issue = saveIssue("production", NOW.minus(Duration.ofDays(2)));
        ErrorEvent oldest = saveEvent(issue, NOW.minus(Duration.ofDays(2)));
        ErrorEvent tiedFirst = saveEvent(issue, NOW.minus(Duration.ofHours(1)));
        ErrorEvent tiedSecond = saveEvent(issue, NOW.minus(Duration.ofHours(1)));
        ErrorEvent newest = saveEvent(issue, NOW.minusSeconds(5));
        saveEvent(saveIssue("staging", NOW), NOW);

        Page<ErrorEvent> page = service.getEvents(issue.getId(), 0, 10);

        List<UUID> tiedIdsDescending = List.of(tiedFirst.getId(), tiedSecond.getId()).stream()
                .sorted(Comparator.comparing(UUID::toString).reversed())
                .toList();
        assertThat(page.getContent()).extracting(ErrorEvent::getId).containsExactly(
                newest.getId(), tiedIdsDescending.get(0), tiedIdsDescending.get(1), oldest.getId());
        assertThat(page.getTotalElements()).isEqualTo(4);
    }

    @Test
    void pagesThroughTheHistoryWithoutRepeatingOrSkippingEvents() {
        Issue issue = saveIssue("production", NOW.minus(Duration.ofHours(1)));
        List<UUID> stored = IntStream.range(0, 7)
                .mapToObj(index -> saveEvent(issue, NOW.minus(Duration.ofMinutes(index % 3))).getId())
                .toList();

        Page<ErrorEvent> first = service.getEvents(issue.getId(), 0, 3);
        Page<ErrorEvent> second = service.getEvents(issue.getId(), 1, 3);
        Page<ErrorEvent> third = service.getEvents(issue.getId(), 2, 3);
        Page<ErrorEvent> pastTheEnd = service.getEvents(issue.getId(), 3, 3);

        List<UUID> paged = List.of(first, second, third).stream()
                .flatMap(page -> page.getContent().stream())
                .map(ErrorEvent::getId)
                .toList();
        assertThat(paged).hasSize(7).containsExactlyInAnyOrderElementsOf(stored).doesNotHaveDuplicates();
        assertThat(first.getTotalPages()).isEqualTo(3);
        assertThat(third.getContent()).hasSize(1);
        assertThat(pastTheEnd.getContent()).isEmpty();
        assertThat(pastTheEnd.getTotalElements()).isEqualTo(7);
    }

    @Test
    void returnsAnEmptyPageForAnIssueWithoutStoredEvents() {
        Issue issue = saveIssue("production", NOW);

        Page<ErrorEvent> page = service.getEvents(issue.getId(), 0, 20);

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    void rejectsEventRequestsForAnUnknownIssue() {
        UUID unknownId = UUID.randomUUID();

        assertThatThrownBy(() -> service.getEvents(unknownId, 0, 20)).isInstanceOf(IssueNotFoundException.class);
    }

    @Test
    void calculatesStatisticsThatMatchThePersistedEvents() {
        Issue issue = saveIssue("production", NOW.minus(Duration.ofDays(20)));
        saveEvent(issue, NOW.minus(Duration.ofDays(20)));
        saveEvent(issue, Instant.parse("2026-09-29T23:59:59.999999Z"));
        saveEvent(issue, Instant.parse("2026-09-30T00:00:00Z"));
        saveEvent(issue, NOW.minus(Duration.ofDays(7)).minus(ONE_MICROSECOND));
        saveEvent(issue, NOW.minus(Duration.ofDays(7)));
        saveEvent(issue, NOW.minus(Duration.ofDays(7)).plusSeconds(1));
        saveEvent(issue, NOW.minus(Duration.ofHours(24)).minus(ONE_MICROSECOND));
        saveEvent(issue, NOW.minus(Duration.ofHours(24)));
        saveEvent(issue, Instant.parse("2026-10-12T23:59:59.999999Z"));
        saveEvent(issue, Instant.parse("2026-10-13T00:00:00Z"));
        saveEvent(issue, NOW);
        saveEvent(saveIssue("staging", NOW), NOW);
        jdbcTemplate.update("update issues set occurrence_count = 11 where id = ?", issue.getId());
        entityManager.clear();

        IssueStatistics statistics = service.getStatistics(issue.getId());

        assertThat(statistics.issueId()).isEqualTo(issue.getId());
        assertThat(statistics.occurrenceCount()).isEqualTo(11);
        assertThat(statistics.storedEventCount()).isEqualTo(11);
        assertThat(statistics.calculatedAt()).isEqualTo(NOW);
        assertThat(statistics.eventsLast24Hours()).isEqualTo(4);
        assertThat(statistics.eventsLast7Days()).isEqualTo(7);
        assertThat(statistics.eventsLast24Hours()).isEqualTo(countSince(issue, NOW.minus(Duration.ofHours(24))));
        assertThat(statistics.eventsLast7Days()).isEqualTo(countSince(issue, NOW.minus(Duration.ofDays(7))));

        List<DailyEventCount> daily = statistics.dailyEventCounts();
        assertThat(daily).hasSize(IssueHistoryService.DAILY_COUNT_DAYS);
        assertThat(daily.getFirst().date()).isEqualTo(LocalDate.parse("2026-09-30"));
        assertThat(daily.getLast().date()).isEqualTo(LocalDate.parse("2026-10-13"));
        assertThat(daily).filteredOn(count -> count.count() > 0).containsExactly(
                new DailyEventCount(LocalDate.parse("2026-09-30"), 1),
                new DailyEventCount(LocalDate.parse("2026-10-06"), 3),
                new DailyEventCount(LocalDate.parse("2026-10-12"), 3),
                new DailyEventCount(LocalDate.parse("2026-10-13"), 2));
        assertThat(daily.stream().mapToLong(DailyEventCount::count).sum()).isEqualTo(
                countSince(issue, Instant.parse("2026-09-30T00:00:00Z")));
    }

    @Test
    void returnsZeroFilledStatisticsForAnIssueWithoutStoredEvents() {
        Issue issue = saveIssue("production", NOW.minus(Duration.ofDays(1)));

        IssueStatistics statistics = service.getStatistics(issue.getId());

        assertThat(statistics.storedEventCount()).isZero();
        assertThat(statistics.eventsLast24Hours()).isZero();
        assertThat(statistics.eventsLast7Days()).isZero();
        assertThat(statistics.firstSeenAt()).isEqualTo(NOW.minus(Duration.ofDays(1)));
        assertThat(statistics.lastSeenAt()).isEqualTo(NOW.minus(Duration.ofDays(1)));
        assertThat(statistics.dailyEventCounts())
                .hasSize(IssueHistoryService.DAILY_COUNT_DAYS)
                .allSatisfy(count -> assertThat(count.count()).isZero());
    }

    @Test
    void rejectsStatisticsRequestsForAnUnknownIssue() {
        UUID unknownId = UUID.randomUUID();

        assertThatThrownBy(() -> service.getStatistics(unknownId)).isInstanceOf(IssueNotFoundException.class);
    }

    private long countSince(Issue issue, Instant since) {
        return jdbcTemplate.queryForObject(
                "select count(*) from events where issue_id = ? and received_at >= ?",
                Long.class, issue.getId(), Timestamp.from(since));
    }

    private Issue saveIssue(String environment, Instant firstSeenAt) {
        return issueRepository.saveAndFlush(new Issue(
                "checkout-service",
                environment,
                FINGERPRINT,
                "java.lang.IllegalStateException",
                "Cart is empty",
                firstSeenAt));
    }

    private ErrorEvent saveEvent(Issue issue, Instant receivedAt) {
        return eventRepository.saveAndFlush(new ErrorEvent(
                issue,
                "java.lang.IllegalStateException",
                "Cart is empty",
                "java.lang.IllegalStateException: Cart is empty\n\tat com.example.Cart.checkout(Cart.java:42)",
                receivedAt.minusSeconds(1),
                receivedAt,
                null,
                null));
    }
}
