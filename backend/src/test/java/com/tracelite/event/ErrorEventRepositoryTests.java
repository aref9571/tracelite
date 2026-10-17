package com.tracelite.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.tracelite.event.domain.ErrorEvent;
import com.tracelite.event.persistence.ErrorEventRepository;
import com.tracelite.event.persistence.EventCountsView;
import com.tracelite.integration.PostgresTestcontainersConfiguration;
import com.tracelite.issue.domain.Issue;
import com.tracelite.issue.persistence.IssueRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestcontainersConfiguration.class)
class ErrorEventRepositoryTests {

    private static final Instant BASE_TIME = Instant.parse("2026-10-11T09:00:00Z");
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("receivedAt"), Sort.Order.desc("id"));

    @Autowired
    private ErrorEventRepository eventRepository;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void persistsAnEventWithItsIssueAndJsonContext() {
        Issue issue = saveIssue("1");
        Map<String, Object> context = Map.of("orderId", "A-1001", "attempt", 3, "flags", List.of("beta"));
        ErrorEvent saved = eventRepository.saveAndFlush(new ErrorEvent(
                issue, "java.lang.IllegalStateException", "Cart is empty",
                "java.lang.IllegalStateException: Cart is empty\n\tat com.example.Cart.checkout(Cart.java:42)",
                BASE_TIME, BASE_TIME.plusSeconds(1), "req-7f3a", context));
        entityManager.clear();

        ErrorEvent loaded = eventRepository.findById(saved.getId()).orElseThrow();

        assertThat(loaded.getIssue().getId()).isEqualTo(issue.getId());
        assertThat(loaded.getExceptionType()).isEqualTo("java.lang.IllegalStateException");
        assertThat(loaded.getMessage()).isEqualTo("Cart is empty");
        assertThat(loaded.getStackTrace()).contains("Cart.java:42");
        assertThat(loaded.getOccurredAt()).isEqualTo(BASE_TIME);
        assertThat(loaded.getReceivedAt()).isEqualTo(BASE_TIME.plusSeconds(1));
        assertThat(loaded.getCorrelationId()).isEqualTo("req-7f3a");
        assertThat(loaded.getContext())
                .containsEntry("orderId", "A-1001")
                .containsEntry("attempt", 3)
                .containsEntry("flags", List.of("beta"));
        assertThat(jdbcTemplate.queryForObject(
                "select jsonb_typeof(context) from events where id = ?", String.class, saved.getId()))
                .isEqualTo("object");
    }

    @Test
    void pagesThroughOneIssuesEventsNewestFirst() {
        Issue issue = saveIssue("1");
        Issue otherIssue = saveIssue("2");
        for (int second = 0; second < 5; second++) {
            saveEvent(issue, BASE_TIME.plusSeconds(second));
        }
        saveEvent(otherIssue, BASE_TIME.plusSeconds(10));
        entityManager.clear();

        Page<ErrorEvent> firstPage = eventRepository.findByIssueId(issue.getId(), PageRequest.of(0, 2, NEWEST_FIRST));
        Page<ErrorEvent> lastPage = eventRepository.findByIssueId(issue.getId(), PageRequest.of(2, 2, NEWEST_FIRST));

        assertThat(firstPage.getTotalElements()).isEqualTo(5);
        assertThat(firstPage.getTotalPages()).isEqualTo(3);
        assertThat(firstPage.getContent()).extracting(ErrorEvent::getReceivedAt)
                .containsExactly(BASE_TIME.plusSeconds(4), BASE_TIME.plusSeconds(3));
        assertThat(lastPage.getContent()).extracting(ErrorEvent::getReceivedAt)
                .containsExactly(BASE_TIME);
        assertThat(countEvents(otherIssue)).isEqualTo(1);
    }

    @Test
    void deletingAnIssueDeletesItsEvents() {
        Issue issue = saveIssue("1");
        Issue otherIssue = saveIssue("2");
        saveEvent(issue, BASE_TIME);
        saveEvent(issue, BASE_TIME.plusSeconds(1));
        saveEvent(otherIssue, BASE_TIME);
        entityManager.flush();
        entityManager.clear();

        jdbcTemplate.update("delete from issues where id = ?", issue.getId());

        assertThat(countEvents(issue)).isZero();
        assertThat(countEvents(otherIssue)).isEqualTo(1);
    }

    @Test
    void countsAllEventsOfAnIssueAndThoseReceivedSinceEachBoundary() {
        Issue issue = saveIssue("1");
        saveEvent(issue, BASE_TIME.minusSeconds(1));
        saveEvent(issue, BASE_TIME);
        saveEvent(issue, BASE_TIME.plusSeconds(1));
        saveEvent(issue, BASE_TIME.plusSeconds(2));
        saveEvent(saveIssue("2"), BASE_TIME.plusSeconds(2));
        entityManager.clear();

        EventCountsView counts = eventRepository.countEvents(issue.getId(), BASE_TIME.plusSeconds(2), BASE_TIME);

        assertThat(counts.getTotalEvents()).isEqualTo(4);
        assertThat(counts.getEventsSinceLastDay()).isEqualTo(1);
        assertThat(counts.getEventsSinceLastWeek()).isEqualTo(3);
    }

    @Test
    void countsNothingForAnIssueWithoutEvents() {
        EventCountsView counts = eventRepository.countEvents(saveIssue("1").getId(), BASE_TIME, BASE_TIME);

        assertThat(counts.getTotalEvents()).isZero();
        assertThat(counts.getEventsSinceLastDay()).isZero();
        assertThat(counts.getEventsSinceLastWeek()).isZero();
    }

    private long countEvents(Issue issue) {
        return jdbcTemplate.queryForObject("select count(*) from events where issue_id = ?", Long.class, issue.getId());
    }

    private Issue saveIssue(String fingerprintDigit) {
        return issueRepository.saveAndFlush(new Issue(
                "checkout-service", "production", fingerprintDigit.repeat(64),
                "java.lang.IllegalStateException", "Cart is empty", BASE_TIME));
    }

    private void saveEvent(Issue issue, Instant receivedAt) {
        eventRepository.saveAndFlush(new ErrorEvent(
                issue, "java.lang.IllegalStateException", "Cart is empty", null,
                receivedAt, receivedAt, null, null));
    }
}
