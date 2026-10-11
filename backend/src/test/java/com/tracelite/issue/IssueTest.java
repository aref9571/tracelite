package com.tracelite.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tracelite.issue.domain.InvalidIssueStatusTransitionException;
import com.tracelite.issue.domain.Issue;
import com.tracelite.issue.domain.IssueStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class IssueTest {

    private static final String FINGERPRINT = "0123456789abcdef".repeat(4);
    private static final Instant FIRST_SEEN = Instant.parse("2026-10-11T09:00:00Z");
    private static final Instant RESOLVED_AT = Instant.parse("2026-10-11T10:00:00Z");

    @Test
    void newIssueIsUnresolvedWithOneOccurrence() {
        Issue issue = newIssue();

        assertThat(issue.getStatus()).isEqualTo(IssueStatus.UNRESOLVED);
        assertThat(issue.getOccurrenceCount()).isEqualTo(1);
        assertThat(issue.getFirstSeenAt()).isEqualTo(FIRST_SEEN);
        assertThat(issue.getLastSeenAt()).isEqualTo(FIRST_SEEN);
        assertThat(issue.getResolvedAt()).isNull();
    }

    @Test
    void resolvingAnUnresolvedIssueRecordsTheResolutionTime() {
        Issue issue = newIssue();

        issue.resolve(RESOLVED_AT);

        assertThat(issue.getStatus()).isEqualTo(IssueStatus.RESOLVED);
        assertThat(issue.getResolvedAt()).isEqualTo(RESOLVED_AT);
    }

    @Test
    void resolvingAResolvedIssueIsRejected() {
        Issue issue = newIssue();
        issue.resolve(RESOLVED_AT);

        assertThatThrownBy(() -> issue.resolve(RESOLVED_AT.plusSeconds(60)))
                .isInstanceOfSatisfying(InvalidIssueStatusTransitionException.class, exception -> {
                    assertThat(exception.getCurrentStatus()).isEqualTo(IssueStatus.RESOLVED);
                    assertThat(exception.getTargetStatus()).isEqualTo(IssueStatus.RESOLVED);
                });
        assertThat(issue.getResolvedAt()).isEqualTo(RESOLVED_AT);
    }

    @Test
    void reopeningAResolvedIssueClearsTheResolutionTime() {
        Issue issue = newIssue();
        issue.resolve(RESOLVED_AT);

        issue.reopen();

        assertThat(issue.getStatus()).isEqualTo(IssueStatus.UNRESOLVED);
        assertThat(issue.getResolvedAt()).isNull();
    }

    @Test
    void reopeningAnUnresolvedIssueIsRejected() {
        Issue issue = newIssue();

        assertThatThrownBy(issue::reopen)
                .isInstanceOf(InvalidIssueStatusTransitionException.class)
                .hasMessage("Cannot change issue status from UNRESOLVED to UNRESOLVED");
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.UNRESOLVED);
    }

    @Test
    void resolvingRequiresATimestamp() {
        Issue issue = newIssue();

        assertThatNullPointerException().isThrownBy(() -> issue.resolve(null));
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.UNRESOLVED);
    }

    @ParameterizedTest
    @CsvSource({
        "UNRESOLVED, RESOLVED, true",
        "RESOLVED, UNRESOLVED, true",
        "UNRESOLVED, UNRESOLVED, false",
        "RESOLVED, RESOLVED, false"
    })
    void statusTransitionRules(IssueStatus from, IssueStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void applicationIsRequired(String application) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Issue(application, "production", FINGERPRINT, "java.lang.IllegalStateException", null, FIRST_SEEN))
                .withMessage("application must not be blank");
    }

    @Test
    void applicationLengthIsLimited() {
        String application = "a".repeat(Issue.MAX_APPLICATION_LENGTH + 1);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Issue(application, "production", FINGERPRINT, "java.lang.IllegalStateException", null, FIRST_SEEN))
                .withMessage("application must be at most 100 characters");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789", "abc123", "g123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"})
    void fingerprintMustBeLowercaseSha256Hex(String fingerprint) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Issue("checkout-service", "production", fingerprint, "java.lang.IllegalStateException", null, FIRST_SEEN))
                .withMessage("fingerprint must be 64 lowercase hexadecimal characters");
    }

    @Test
    void messageIsOptionalButLimited() {
        assertThat(new Issue("checkout-service", "production", FINGERPRINT, "java.lang.NullPointerException", null, FIRST_SEEN).getMessage())
                .isNull();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Issue("checkout-service", "production", FINGERPRINT, "java.lang.NullPointerException",
                        "m".repeat(Issue.MAX_MESSAGE_LENGTH + 1), FIRST_SEEN))
                .withMessage("message must be at most 4000 characters");
    }

    @Test
    void firstSeenTimeIsRequired() {
        assertThatNullPointerException()
                .isThrownBy(() -> new Issue("checkout-service", "production", FINGERPRINT, "java.lang.IllegalStateException", null, null));
    }

    private static Issue newIssue() {
        return new Issue("checkout-service", "production", FINGERPRINT, "java.lang.IllegalStateException", "Cart is empty", FIRST_SEEN);
    }
}
