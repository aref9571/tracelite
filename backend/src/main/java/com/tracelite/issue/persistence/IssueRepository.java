package com.tracelite.issue.persistence;

import com.tracelite.issue.domain.Issue;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IssueRepository extends JpaRepository<Issue, UUID> {

    Optional<Issue> findByApplicationAndEnvironmentAndFingerprint(
            String application, String environment, String fingerprint);

    @Modifying
    @Query(value = """
            UPDATE issues
            SET occurrence_count = occurrence_count + 1,
                last_seen_at = GREATEST(last_seen_at, :seenAt)
            WHERE id = :id
            """, nativeQuery = true)
    int recordOccurrence(@Param("id") UUID id, @Param("seenAt") Instant seenAt);
}
