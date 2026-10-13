package com.tracelite.issue.persistence;

import com.tracelite.issue.domain.Issue;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IssueRepository extends JpaRepository<Issue, UUID>, JpaSpecificationExecutor<Issue> {

    Optional<Issue> findByApplicationAndEnvironmentAndFingerprint(
            String application, String environment, String fingerprint);

    @Query(value = """
            INSERT INTO issues (application, environment, fingerprint, exception_type, message, status,
                                occurrence_count, first_seen_at, last_seen_at)
            VALUES (:application, :environment, :fingerprint, :exceptionType, :message, 'UNRESOLVED',
                    1, :seenAt, :seenAt)
            ON CONFLICT ON CONSTRAINT issues_scope_fingerprint_uk DO UPDATE
            SET occurrence_count = issues.occurrence_count + 1,
                last_seen_at = GREATEST(issues.last_seen_at, EXCLUDED.last_seen_at),
                status = 'UNRESOLVED',
                resolved_at = NULL,
                version = CASE WHEN issues.status = 'RESOLVED' THEN issues.version + 1 ELSE issues.version END
            RETURNING id
            """, nativeQuery = true)
    UUID recordOccurrence(
            @Param("application") String application,
            @Param("environment") String environment,
            @Param("fingerprint") String fingerprint,
            @Param("exceptionType") String exceptionType,
            @Param("message") String message,
            @Param("seenAt") Instant seenAt);
}
