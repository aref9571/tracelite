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
    void recordOccurrenceCreatesAnIssueForANewScopedFingerprint() {
        UUID issueId = recordOccurrence("production", FIRST_SEEN);
        entityManager.clear();

        Issue created = issueRepository.findById(issueId).orElseThrow();
        assertThat(created.getApplication()).isEqualTo("checkout-service");
        assertThat(created.getEnvironment()).isEqualTo("production");
        assertThat(created.getFingerprint()).isEqualTo(FINGERPRINT);
        assertThat(created.getExceptionType()).isEqualTo("java.lang.IllegalStateException");
        assertThat(created.getMessage()).isEqualTo("Cart is empty");
        assertThat(created.getStatus()).isEqualTo(IssueStatus.UNRESOLVED);
        assertThat(created.getOccurrenceCount()).isEqualTo(1);
        assertThat(created.getFirstSeenAt()).isEqualTo(FIRST_SEEN);
        assertThat(created.getLastSeenAt()).isEqualTo(FIRST_SEEN);
        assertThat(created.getVersion()).isZero();
    }

    @Test
    void recordOccurrenceIncrementsTheCountAndAdvancesLastSeen() {
        Issue issue = issueRepository.saveAndFlush(newIssue("production"));
        Instant seenAt = FIRST_SEEN.plusSeconds(300);

        UUID issueId = issueRepository.recordOccurrence("checkout-service", "production", FINGERPRINT,
                "java.lang.IllegalStateException", "A different message", seenAt);
        entityManager.clear();

        Issue reloaded = issueRepository.findById(issue.getId()).orElseThrow();
        assertThat(issueId).isEqualTo(issue.getId());
        assertThat(issueRepository.count()).isEqualTo(1);
        assertThat(reloaded.getOccurrenceCount()).isEqualTo(2);
        assertThat(reloaded.getLastSeenAt()).isEqualTo(seenAt);
        assertThat(reloaded.getFirstSeenAt()).isEqualTo(FIRST_SEEN);
        assertThat(reloaded.getMessage()).isEqualTo("Cart is empty");
        assertThat(reloaded.getVersion()).isZero();
    }

    @Test
    void recordOccurrenceNeverMovesLastSeenBackwards() {
        UUID issueId = recordOccurrence("production", FIRST_SEEN);
        Instant later = FIRST_SEEN.plusSeconds(300);
        recordOccurrence("production", later);

        recordOccurrence("production", FIRST_SEEN.plusSeconds(60));
        entityManager.clear();

        Issue reloaded = issueRepository.findById(issueId).orElseThrow();
        assertThat(reloaded.getOccurrenceCount()).isEqualTo(3);
        assertThat(reloaded.getLastSeenAt()).isEqualTo(later);
    }

    @Test
    void recordOccurrenceKeepsScopesSeparate() {
        UUID production = recordOccurrence("production", FIRST_SEEN);
        UUID staging = recordOccurrence("staging", FIRST_SEEN);

        assertThat(staging).isNotEqualTo(production);
        assertThat(issueRepository.count()).isEqualTo(2);
    }

    @Test
    void recordOccurrenceReopensAResolvedIssueAndIncrementsTheVersion() {
        Issue issue = newIssue("production");
        issue.resolve(FIRST_SEEN.plusSeconds(60));
        issueRepository.saveAndFlush(issue);
        long versionWhenResolved = issue.getVersion();

        recordOccurrence("production", FIRST_SEEN.plusSeconds(120));
        entityManager.clear();

        Issue reopened = issueRepository.findById(issue.getId()).orElseThrow();
        assertThat(reopened.getStatus()).isEqualTo(IssueStatus.UNRESOLVED);
        assertThat(reopened.getResolvedAt()).isNull();
        assertThat(reopened.getOccurrenceCount()).isEqualTo(2);
        assertThat(reopened.getVersion()).isEqualTo(versionWhenResolved + 1);
    }

    @Test
    void aStatusChangeBasedOnAVersionReadBeforeAReopenIsRejected() {
        Issue issue = newIssue("production");
        issue.resolve(FIRST_SEEN.plusSeconds(60));
        issueRepository.saveAndFlush(issue);
        recordOccurrence("production", FIRST_SEEN.plusSeconds(120));

        issue.reopen();

        assertThatThrownBy(() -> issueRepository.saveAndFlush(issue))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    private UUID recordOccurrence(String environment, Instant seenAt) {
        return issueRepository.recordOccurrence("checkout-service", environment, FINGERPRINT,
                "java.lang.IllegalStateException", "Cart is empty", seenAt);
    }

    private static Issue newIssue(String environment) {
        return new Issue("checkout-service", environment, FINGERPRINT, "java.lang.IllegalStateException", "Cart is empty", FIRST_SEEN);
    }
}
