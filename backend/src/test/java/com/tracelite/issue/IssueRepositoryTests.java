package com.tracelite.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tracelite.integration.PostgresTestcontainersConfiguration;
import com.tracelite.issue.domain.Issue;
import com.tracelite.issue.domain.IssueStatus;
import com.tracelite.issue.persistence.IssueRepository;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestcontainersConfiguration.class)
class IssueRepositoryTests {

    private static final String FINGERPRINT = "a1b2c3d4".repeat(8);
    private static final Instant FIRST_SEEN = Instant.parse("2026-10-11T09:00:00Z");

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void persistsAndReloadsAnIssue() {
        Issue saved = issueRepository.saveAndFlush(newIssue("production"));
        entityManager.clear();

        Issue loaded = issueRepository.findById(saved.getId()).orElseThrow();

        assertThat(loaded.getId()).isNotNull();
        assertThat(loaded.getApplication()).isEqualTo("checkout-service");
        assertThat(loaded.getEnvironment()).isEqualTo("production");
        assertThat(loaded.getFingerprint()).isEqualTo(FINGERPRINT);
        assertThat(loaded.getExceptionType()).isEqualTo("java.lang.IllegalStateException");
        assertThat(loaded.getMessage()).isEqualTo("Cart is empty");
        assertThat(loaded.getStatus()).isEqualTo(IssueStatus.UNRESOLVED);
        assertThat(loaded.getOccurrenceCount()).isEqualTo(1);
        assertThat(loaded.getFirstSeenAt()).isEqualTo(FIRST_SEEN);
        assertThat(loaded.getLastSeenAt()).isEqualTo(FIRST_SEEN);
        assertThat(loaded.getResolvedAt()).isNull();
        assertThat(loaded.getVersion()).isZero();
    }

    @Test
    void findsAnIssueByItsScopedFingerprint() {
        Issue saved = issueRepository.saveAndFlush(newIssue("production"));

        assertThat(issueRepository.findByApplicationAndEnvironmentAndFingerprint("checkout-service", "production", FINGERPRINT))
                .get().extracting(Issue::getId).isEqualTo(saved.getId());
        assertThat(issueRepository.findByApplicationAndEnvironmentAndFingerprint("checkout-service", "staging", FINGERPRINT))
                .isEmpty();
    }

    @Test
    void rejectsASecondIssueWithTheSameScopedFingerprint() {
        issueRepository.saveAndFlush(newIssue("production"));

        assertThatThrownBy(() -> issueRepository.saveAndFlush(newIssue("production")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("issues_scope_fingerprint_uk");
    }

    @Test
    void allowsTheSameFingerprintInAnotherEnvironment() {
        issueRepository.saveAndFlush(newIssue("production"));
        issueRepository.saveAndFlush(newIssue("staging"));

        assertThat(issueRepository.count()).isEqualTo(2);
    }

    @Test
    void persistsStatusChangesAndIncrementsTheVersion() {
        Issue issue = issueRepository.saveAndFlush(newIssue("production"));
        Instant resolvedAt = Instant.parse("2026-10-11T10:15:30Z");

        issue.resolve(resolvedAt);
        issueRepository.saveAndFlush(issue);
        entityManager.clear();

        Issue resolved = issueRepository.findById(issue.getId()).orElseThrow();
        assertThat(resolved.getStatus()).isEqualTo(IssueStatus.RESOLVED);
        assertThat(resolved.getResolvedAt()).isEqualTo(resolvedAt);
        assertThat(resolved.getVersion()).isEqualTo(1);

        resolved.reopen();
        issueRepository.saveAndFlush(resolved);
        entityManager.clear();

        Issue reopened = issueRepository.findById(issue.getId()).orElseThrow();
        assertThat(reopened.getStatus()).isEqualTo(IssueStatus.UNRESOLVED);
        assertThat(reopened.getResolvedAt()).isNull();
        assertThat(reopened.getVersion()).isEqualTo(2);
    }

    @Test
    void rejectsAStatusChangeBasedOnAStaleVersion() {
        Issue issue = issueRepository.saveAndFlush(newIssue("production"));
        UUID issueId = issue.getId();
        jdbcTemplate.update("update issues set version = version + 1 where id = ?", issueId);

        issue.resolve(Instant.parse("2026-10-11T10:15:30Z"));

        assertThatThrownBy(() -> issueRepository.saveAndFlush(issue))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void statusChangesDoNotOverwriteCountersUpdatedInTheDatabase() {
        Issue issue = issueRepository.saveAndFlush(newIssue("production"));
        Instant lastSeen = Instant.parse("2026-10-11T09:30:00Z");
        jdbcTemplate.update("update issues set occurrence_count = 5, last_seen_at = ? where id = ?",
                Timestamp.from(lastSeen), issue.getId());

        issue.resolve(Instant.parse("2026-10-11T10:15:30Z"));
        issueRepository.saveAndFlush(issue);
        entityManager.clear();

        Issue reloaded = issueRepository.findById(issue.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(IssueStatus.RESOLVED);
        assertThat(reloaded.getOccurrenceCount()).isEqualTo(5);
        assertThat(reloaded.getLastSeenAt()).isEqualTo(lastSeen);
    }

    @Test
    void recordOccurrenceIncrementsTheCountAndAdvancesLastSeen() {
        Issue issue = issueRepository.saveAndFlush(newIssue("production"));
        Instant seenAt = FIRST_SEEN.plusSeconds(300);

        int updated = issueRepository.recordOccurrence(issue.getId(), seenAt);
        entityManager.clear();

        Issue reloaded = issueRepository.findById(issue.getId()).orElseThrow();
        assertThat(updated).isEqualTo(1);
        assertThat(reloaded.getOccurrenceCount()).isEqualTo(2);
        assertThat(reloaded.getLastSeenAt()).isEqualTo(seenAt);
        assertThat(reloaded.getFirstSeenAt()).isEqualTo(FIRST_SEEN);
        assertThat(reloaded.getVersion()).isZero();
    }

    @Test
    void recordOccurrenceNeverMovesLastSeenBackwards() {
        Issue issue = issueRepository.saveAndFlush(newIssue("production"));
        Instant later = FIRST_SEEN.plusSeconds(300);
        issueRepository.recordOccurrence(issue.getId(), later);

        issueRepository.recordOccurrence(issue.getId(), FIRST_SEEN.plusSeconds(60));
        entityManager.clear();

        Issue reloaded = issueRepository.findById(issue.getId()).orElseThrow();
        assertThat(reloaded.getOccurrenceCount()).isEqualTo(3);
        assertThat(reloaded.getLastSeenAt()).isEqualTo(later);
    }

    @Test
    void recordOccurrenceUpdatesNothingForAnUnknownIssue() {
        assertThat(issueRepository.recordOccurrence(UUID.randomUUID(), FIRST_SEEN)).isZero();
    }

    private static Issue newIssue(String environment) {
        return new Issue("checkout-service", environment, FINGERPRINT, "java.lang.IllegalStateException", "Cart is empty", FIRST_SEEN);
    }
}
