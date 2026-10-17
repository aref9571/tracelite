package com.tracelite.event.persistence;

import com.tracelite.event.domain.ErrorEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ErrorEventRepository extends JpaRepository<ErrorEvent, UUID> {

    Page<ErrorEvent> findByIssueId(UUID issueId, Pageable pageable);

    @Query(value = """
            SELECT count(*) AS "totalEvents",
                   count(*) FILTER (WHERE received_at >= :lastDaySince) AS "eventsSinceLastDay",
                   count(*) FILTER (WHERE received_at >= :lastWeekSince) AS "eventsSinceLastWeek"
            FROM events
            WHERE issue_id = :issueId
            """, nativeQuery = true)
    EventCountsView countEvents(
            @Param("issueId") UUID issueId,
            @Param("lastDaySince") Instant lastDaySince,
            @Param("lastWeekSince") Instant lastWeekSince);

    @Query(value = """
            SELECT to_char(received_at AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS day, count(*) AS "eventCount"
            FROM events
            WHERE issue_id = :issueId AND received_at >= :since
            GROUP BY day
            ORDER BY day
            """, nativeQuery = true)
    List<DailyEventCountView> countPerUtcDay(@Param("issueId") UUID issueId, @Param("since") Instant since);
}
