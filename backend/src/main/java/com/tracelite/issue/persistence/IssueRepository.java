package com.tracelite.issue.persistence;

import com.tracelite.issue.domain.Issue;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IssueRepository extends JpaRepository<Issue, UUID> {

    Optional<Issue> findByApplicationAndEnvironmentAndFingerprint(
            String application, String environment, String fingerprint);
}
