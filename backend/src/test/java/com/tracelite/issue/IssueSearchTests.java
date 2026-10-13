package com.tracelite.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tracelite.integration.PostgresTestcontainersConfiguration;
import com.tracelite.issue.application.InvalidIssueQueryException;
import com.tracelite.issue.application.IssueNotFoundException;
import com.tracelite.issue.application.IssueQueryService;
import com.tracelite.issue.application.IssueSearchCriteria;
import com.tracelite.issue.application.IssueSortField;
import com.tracelite.issue.domain.Issue;
import com.tracelite.issue.domain.IssueStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestcontainersConfiguration.class, IssueQueryService.class})
class IssueSearchTests {

    private static final Instant BASE_TIME = Instant.parse("2026-10-01T00:00:00Z");
    private static final IssueSearchCriteria NO_FILTERS = new IssueSearchCriteria(null, null, null, null, null);

    @Autowired
    private IssueQueryService issueQueryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void listsAllIssuesByLastSeenNewestFirstByDefault() {
        UUID oldest = insertIssue(seed().lastSeenHours(1));
        UUID newest = insertIssue(seed().lastSeenHours(3));
        UUID middle = insertIssue(seed().lastSeenHours(2));

        Page<Issue> page = search(NO_FILTERS);

        assertThat(ids(page)).containsExactly(newest, middle, oldest);
    }

    @Test
    void breaksTiesOnTheSortValueByAscendingId() {
        List<UUID> tied = List.of(
                insertIssue(seed().id("00000000-0000-0000-0000-000000000003").lastSeenHours(5)),
                insertIssue(seed().id("00000000-0000-0000-0000-000000000001").lastSeenHours(5)),
                insertIssue(seed().id("00000000-0000-0000-0000-000000000002").lastSeenHours(5)));

        Page<Issue> descending = search(NO_FILTERS, IssueSortField.LAST_SEEN, Sort.Direction.DESC, 0, 10);
        Page<Issue> ascending = search(NO_FILTERS, IssueSortField.LAST_SEEN, Sort.Direction.ASC, 0, 10);

        List<UUID> byId = tied.stream().sorted().toList();
        assertThat(ids(descending)).containsExactlyElementsOf(byId);
        assertThat(ids(ascending)).containsExactlyElementsOf(byId);
    }

    @Test
    void returnsEveryIssueExactlyOnceAcrossPagesWhenSortValuesTie() {
        for (int i = 0; i < 7; i++) {
            insertIssue(seed().lastSeenHours(i % 2).occurrences(4));
        }

        List<UUID> collected = new ArrayList<>();
        for (int pageNumber = 0; pageNumber < 3; pageNumber++) {
            collected.addAll(ids(search(NO_FILTERS, IssueSortField.OCCURRENCE_COUNT, Sort.Direction.DESC, pageNumber, 3)));
        }

        assertThat(collected).hasSize(7).doesNotHaveDuplicates();
    }

    @Test
    void sortsByFirstSeenAndByOccurrenceCount() {
        UUID earlyFrequent = insertIssue(seed().firstSeenHours(1).lastSeenHours(10).occurrences(50));
        UUID lateRare = insertIssue(seed().firstSeenHours(5).lastSeenHours(6).occurrences(2));
        UUID middle = insertIssue(seed().firstSeenHours(3).lastSeenHours(4).occurrences(9));

        assertThat(ids(search(NO_FILTERS, IssueSortField.FIRST_SEEN, Sort.Direction.ASC, 0, 10)))
                .containsExactly(earlyFrequent, middle, lateRare);
        assertThat(ids(search(NO_FILTERS, IssueSortField.FIRST_SEEN, Sort.Direction.DESC, 0, 10)))
                .containsExactly(lateRare, middle, earlyFrequent);
        assertThat(ids(search(NO_FILTERS, IssueSortField.OCCURRENCE_COUNT, Sort.Direction.DESC, 0, 10)))
                .containsExactly(earlyFrequent, middle, lateRare);
    }

    @Test
    void reportsAccuratePaginationMetadata() {
        for (int i = 0; i < 5; i++) {
            insertIssue(seed().lastSeenHours(i));
        }

        Page<Issue> first = search(NO_FILTERS, IssueSortField.LAST_SEEN, Sort.Direction.DESC, 0, 2);
        Page<Issue> last = search(NO_FILTERS, IssueSortField.LAST_SEEN, Sort.Direction.DESC, 2, 2);
        Page<Issue> beyond = search(NO_FILTERS, IssueSortField.LAST_SEEN, Sort.Direction.DESC, 3, 2);

        assertThat(first.getContent()).hasSize(2);
        assertThat(first.getTotalElements()).isEqualTo(5);
        assertThat(first.getTotalPages()).isEqualTo(3);
        assertThat(last.getContent()).hasSize(1);
        assertThat(last.getTotalElements()).isEqualTo(5);
        assertThat(beyond.getContent()).isEmpty();
        assertThat(beyond.getTotalElements()).isEqualTo(5);
        assertThat(beyond.getNumber()).isEqualTo(3);
    }

    @Test
    void returnsAnEmptyPageWhenNoIssuesExist() {
        Page<Issue> page = search(NO_FILTERS);

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
        assertThat(page.getTotalPages()).isZero();
    }

    @Test
    void filtersByStatus() {
        UUID unresolved = insertIssue(seed());
        UUID resolved = insertIssue(seed().resolved());

        assertThat(ids(search(criteria(IssueStatus.UNRESOLVED, null, null, null, null)))).containsExactly(unresolved);
        assertThat(ids(search(criteria(IssueStatus.RESOLVED, null, null, null, null)))).containsExactly(resolved);
    }

    @Test
    void filtersByExactEnvironment() {
        UUID production = insertIssue(seed().environment("production"));
        insertIssue(seed().environment("staging"));
        insertIssue(seed().environment("production-eu"));

        assertThat(ids(search(criteria(null, "production", null, null, null)))).containsExactly(production);
        assertThat(ids(search(criteria(null, "Production", null, null, null)))).isEmpty();
    }

    @Test
    void combinesFiltersWithAnd() {
        UUID match = insertIssue(seed().environment("production").exceptionType("java.io.IOException"));
        insertIssue(seed().environment("production").exceptionType("java.io.IOException").resolved());
        insertIssue(seed().environment("staging").exceptionType("java.io.IOException"));
        insertIssue(seed().environment("production").exceptionType("java.lang.IllegalStateException"));

        Page<Issue> page = search(criteria(IssueStatus.UNRESOLVED, "production", null, null, "ioexception"));

        assertThat(ids(page)).containsExactly(match);
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void filtersByLastSeenRangeWithInclusiveStartAndExclusiveEnd() {
        insertIssue(seed().lastSeenHours(1));
        UUID atStart = insertIssue(seed().lastSeenHours(2));
        UUID inside = insertIssue(seed().lastSeenHours(3));
        insertIssue(seed().lastSeenHours(4));

        Instant from = BASE_TIME.plusSeconds(2 * 3600);
        Instant to = BASE_TIME.plusSeconds(4 * 3600);

        assertThat(ids(search(criteria(null, null, from, to, null)))).containsExactly(inside, atStart);
        assertThat(ids(search(criteria(null, null, from, null, null)))).hasSize(3);
        assertThat(ids(search(criteria(null, null, null, to, null)))).hasSize(3);
    }

    @Test
    void rejectsARangeWhoseEndIsNotAfterItsStart() {
        Instant time = BASE_TIME.plusSeconds(3600);

        assertThatThrownBy(() -> search(criteria(null, null, time, time, null)))
                .isInstanceOf(InvalidIssueQueryException.class)
                .satisfies(error -> assertThat(((InvalidIssueQueryException) error).getField()).isEqualTo("lastSeenTo"));
        assertThatThrownBy(() -> search(criteria(null, null, time, time.minusSeconds(1), null)))
                .isInstanceOf(InvalidIssueQueryException.class);
    }

    @Test
    void searchesExceptionTypeAndMessageCaseInsensitively() {
        UUID byType = insertIssue(seed().exceptionType("com.example.PaymentDeclinedException").message("Card rejected"));
        UUID byMessage = insertIssue(seed().exceptionType("java.lang.IllegalStateException").message("Payment gateway timed out"));
        insertIssue(seed().exceptionType("java.lang.NullPointerException").message(null));

        assertThat(ids(search(criteria(null, null, null, null, "PAYMENT")))).containsExactlyInAnyOrder(byType, byMessage);
        assertThat(ids(search(criteria(null, null, null, null, "  gateway  ")))).containsExactly(byMessage);
        assertThat(ids(search(criteria(null, null, null, null, "missing")))).isEmpty();
    }

    @Test
    void treatsLikeWildcardsInSearchTextAsLiteralCharacters() {
        UUID percent = insertIssue(seed().message("Disk 100% full"));
        UUID underscore = insertIssue(seed().message("Missing column user_id"));
        UUID backslash = insertIssue(seed().message("Path C:\\temp not found"));
        insertIssue(seed().message("Disk 1000 full and userXid"));

        assertThat(ids(search(criteria(null, null, null, null, "100%")))).containsExactly(percent);
        assertThat(ids(search(criteria(null, null, null, null, "%")))).containsExactly(percent);
        assertThat(ids(search(criteria(null, null, null, null, "user_id")))).containsExactly(underscore);
        assertThat(ids(search(criteria(null, null, null, null, "C:\\temp")))).containsExactly(backslash);
    }

    @Test
    void ignoresBlankSearchText() {
        insertIssue(seed());
        insertIssue(seed());

        assertThat(search(criteria(null, null, null, null, "   ")).getTotalElements()).isEqualTo(2);
    }

    @Test
    void returnsAnIssueById() {
        UUID id = insertIssue(seed().environment("staging").message("Cart is empty"));

        Issue issue = issueQueryService.getIssue(id);

        assertThat(issue.getId()).isEqualTo(id);
        assertThat(issue.getEnvironment()).isEqualTo("staging");
        assertThat(issue.getMessage()).isEqualTo("Cart is empty");
    }

    @Test
    void throwsNotFoundForAnUnknownId() {
        UUID unknown = UUID.fromString("7b0c6a3e-0000-4000-8000-000000000000");

        assertThatThrownBy(() -> issueQueryService.getIssue(unknown)).isInstanceOf(IssueNotFoundException.class);
    }

    @Test
    void searchIndexesAreUsableForCaseInsensitiveContainsQueries() {
        jdbcTemplate.execute("SET LOCAL enable_seqscan = off");

        String plan = String.join("\n", jdbcTemplate.queryForList(
                "EXPLAIN SELECT id FROM issues WHERE lower(exception_type) LIKE '%timeout%' OR lower(message) LIKE '%timeout%'",
                String.class));

        assertThat(plan).contains("issues_exception_type_trgm_idx").contains("issues_message_trgm_idx");
    }

    private Page<Issue> search(IssueSearchCriteria criteria) {
        return search(criteria, IssueSortField.LAST_SEEN, Sort.Direction.DESC, 0, 20);
    }

    private Page<Issue> search(
            IssueSearchCriteria criteria, IssueSortField sortField, Sort.Direction direction, int page, int size) {
        return issueQueryService.search(criteria, sortField, direction, page, size);
    }

    private static IssueSearchCriteria criteria(
            IssueStatus status, String environment, Instant lastSeenFrom, Instant lastSeenTo, String text) {
        return new IssueSearchCriteria(status, environment, lastSeenFrom, lastSeenTo, text);
    }

    private static List<UUID> ids(Page<Issue> page) {
        return page.getContent().stream().map(Issue::getId).toList();
    }

    private UUID insertIssue(IssueSeed seed) {
        UUID id = seed.id != null ? seed.id : UUID.randomUUID();
        Instant firstSeen = BASE_TIME.plusSeconds(seed.firstSeenHours * 3600L);
        Instant lastSeen = BASE_TIME.plusSeconds(Math.max(seed.lastSeenHours, seed.firstSeenHours) * 3600L);
        jdbcTemplate.update("""
                        insert into issues (id, application, environment, fingerprint, exception_type, message, status,
                                            occurrence_count, first_seen_at, last_seen_at, resolved_at)
                        values (?, 'checkout-service', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                id,
                seed.environment,
                fingerprintFor(id),
                seed.exceptionType,
                seed.message,
                seed.resolved ? "RESOLVED" : "UNRESOLVED",
                seed.occurrences,
                Timestamp.from(firstSeen),
                Timestamp.from(lastSeen),
                seed.resolved ? Timestamp.from(lastSeen) : null);
        return id;
    }

    private static String fingerprintFor(UUID id) {
        return (id.toString().replace("-", "") + "0".repeat(32)).substring(0, 64);
    }

    private static IssueSeed seed() {
        return new IssueSeed();
    }

    private static final class IssueSeed {

        private UUID id;
        private String environment = "production";
        private String exceptionType = "java.lang.IllegalStateException";
        private String message = "Something failed";
        private boolean resolved;
        private long occurrences = 1;
        private int firstSeenHours;
        private int lastSeenHours;

        IssueSeed id(String value) {
            this.id = UUID.fromString(value);
            return this;
        }

        IssueSeed environment(String value) {
            this.environment = value;
            return this;
        }

        IssueSeed exceptionType(String value) {
            this.exceptionType = value;
            return this;
        }

        IssueSeed message(String value) {
            this.message = value;
            return this;
        }

        IssueSeed resolved() {
            this.resolved = true;
            return this;
        }

        IssueSeed occurrences(long value) {
            this.occurrences = value;
            return this;
        }

        IssueSeed firstSeenHours(int value) {
            this.firstSeenHours = value;
            return this;
        }

        IssueSeed lastSeenHours(int value) {
            this.lastSeenHours = value;
            return this;
        }
    }
}
