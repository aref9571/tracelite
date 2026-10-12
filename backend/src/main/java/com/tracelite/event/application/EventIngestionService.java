package com.tracelite.event.application;

import com.tracelite.config.IngestionProperties;
import com.tracelite.event.domain.ErrorEvent;
import com.tracelite.event.persistence.ErrorEventRepository;
import com.tracelite.issue.application.FingerprintGenerator;
import com.tracelite.issue.domain.Issue;
import com.tracelite.issue.persistence.IssueRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventIngestionService {

    private static final Logger log = LoggerFactory.getLogger(EventIngestionService.class);

    private static final char NULL_CHARACTER = '\u0000';

    private final IssueRepository issueRepository;
    private final ErrorEventRepository errorEventRepository;
    private final FingerprintGenerator fingerprintGenerator;
    private final IngestionProperties properties;
    private final Clock clock;

    public EventIngestionService(
            IssueRepository issueRepository,
            ErrorEventRepository errorEventRepository,
            FingerprintGenerator fingerprintGenerator,
            IngestionProperties properties,
            Clock clock) {
        this.issueRepository = issueRepository;
        this.errorEventRepository = errorEventRepository;
        this.fingerprintGenerator = fingerprintGenerator;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public ErrorEvent ingest(ErrorEventSubmission submission) {
        Instant receivedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant occurredAt = submission.occurredAt().truncatedTo(ChronoUnit.MICROS);
        requireOccurredAtWithinBounds(occurredAt, receivedAt);
        requireNoNullCharacters(submission);

        Issue issue = findOrCreateIssue(submission, receivedAt);
        ErrorEvent event = errorEventRepository.save(new ErrorEvent(
                issue,
                submission.exceptionType(),
                submission.message(),
                submission.stackTrace(),
                occurredAt,
                receivedAt,
                submission.correlationId(),
                submission.context()));

        log.debug("Stored event {} for issue {}", event.getId(), issue.getId());
        return event;
    }

    private Issue findOrCreateIssue(ErrorEventSubmission submission, Instant receivedAt) {
        String fingerprint = fingerprintGenerator.generate(submission.exceptionType(), submission.stackTrace());
        UUID issueId = issueRepository.recordOccurrence(
                submission.application(),
                submission.environment(),
                fingerprint,
                submission.exceptionType(),
                submission.message(),
                receivedAt);
        return issueRepository.getReferenceById(issueId);
    }

    private void requireOccurredAtWithinBounds(Instant occurredAt, Instant receivedAt) {
        Instant latestAllowed = receivedAt.plus(properties.maxClockSkew());
        Instant earliestAllowed = receivedAt.minus(properties.maxEventAge());
        if (occurredAt.isAfter(latestAllowed)) {
            throw new InvalidErrorEventException("occurredAt", "must not be after " + latestAllowed);
        }
        if (occurredAt.isBefore(earliestAllowed)) {
            throw new InvalidErrorEventException("occurredAt", "must not be before " + earliestAllowed);
        }
    }

    private static void requireNoNullCharacters(ErrorEventSubmission submission) {
        requireNoNullCharacters("application", submission.application());
        requireNoNullCharacters("environment", submission.environment());
        requireNoNullCharacters("exceptionType", submission.exceptionType());
        requireNoNullCharacters("message", submission.message());
        requireNoNullCharacters("stackTrace", submission.stackTrace());
        requireNoNullCharacters("correlationId", submission.correlationId());
        requireNoNullCharacters("context", submission.context());
    }

    private static void requireNoNullCharacters(String field, Object value) {
        if (value instanceof String text && text.indexOf(NULL_CHARACTER) >= 0) {
            throw new InvalidErrorEventException(field, "must not contain null characters");
        }
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, entryValue) -> {
                requireNoNullCharacters(field, key);
                requireNoNullCharacters(field, entryValue);
            });
        }
        if (value instanceof Collection<?> collection) {
            collection.forEach(element -> requireNoNullCharacters(field, element));
        }
    }
}
