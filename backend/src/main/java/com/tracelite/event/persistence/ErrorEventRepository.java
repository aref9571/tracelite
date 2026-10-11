package com.tracelite.event.persistence;

import com.tracelite.event.domain.ErrorEvent;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ErrorEventRepository extends JpaRepository<ErrorEvent, UUID> {

    Page<ErrorEvent> findByIssueId(UUID issueId, Pageable pageable);

    long countByIssueId(UUID issueId);
}
