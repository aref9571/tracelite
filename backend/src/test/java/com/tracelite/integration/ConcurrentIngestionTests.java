package com.tracelite.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ConcurrentIngestionTests extends ApiIntegrationTestSupport {

    private static final int CONCURRENT_REQUESTS = 12;

    @Test
    void simultaneousFirstEventsForOneFingerprintCreateExactlyOneIssue() throws Exception {
        String event = event("production", "java.lang.IllegalStateException", Instant.now().minusSeconds(10));

        List<ResponseEntity<Map<String, Object>>> responses =
                runSimultaneously(CONCURRENT_REQUESTS, () -> postEvent(event));

        assertThat(responses).allSatisfy(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED));
        assertThat(responses).extracting(response -> response.getBody().get("issueId")).containsOnly(
                responses.getFirst().getBody().get("issueId"));
        assertThat(countRows("issues")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select occurrence_count from issues", Long.class))
                .isEqualTo(CONCURRENT_REQUESTS);
        assertThat(countRows("events")).isEqualTo(CONCURRENT_REQUESTS);
    }

    @Test
    void simultaneousEventsForAnExistingIssueAreAllCounted() throws Exception {
        String event = event("production", "java.lang.IllegalStateException", Instant.now().minusSeconds(10));
        ingest(event);

        List<ResponseEntity<Map<String, Object>>> responses =
                runSimultaneously(CONCURRENT_REQUESTS, () -> postEvent(event));

        assertThat(responses).allSatisfy(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED));
        assertThat(countRows("issues")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select occurrence_count from issues", Long.class))
                .isEqualTo(CONCURRENT_REQUESTS + 1);
        assertThat(jdbcTemplate.queryForObject(
                "select last_seen_at = (select max(received_at) from events) from issues", Boolean.class)).isTrue();
    }

    @Test
    void simultaneousEventsForAResolvedIssueReopenItExactlyOnce() throws Exception {
        String event = event("production", "java.lang.IllegalStateException", Instant.now().minusSeconds(10));
        String issueId = ingest(event);
        assertThat(postAsManagement("/api/v1/issues/" + issueId + "/resolve").getStatusCode()).isEqualTo(HttpStatus.OK);

        List<ResponseEntity<Map<String, Object>>> responses =
                runSimultaneously(CONCURRENT_REQUESTS, () -> postEvent(event));

        assertThat(responses).allSatisfy(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED));
        Map<String, Object> issue = jdbcTemplate.queryForMap(
                "select status, resolved_at, occurrence_count, version from issues where id = ?::uuid", issueId);
        assertThat(issue)
                .containsEntry("status", "UNRESOLVED")
                .containsEntry("occurrence_count", (long) CONCURRENT_REQUESTS + 1)
                .containsEntry("version", 2L);
        assertThat(issue.get("resolved_at")).isNull();
    }

    @Test
    void resolveRequestsDuringIngestionNeverLoseOccurrences() throws Exception {
        String event = event("production", "java.lang.IllegalStateException", Instant.now().minusSeconds(10));
        String issueId = ingest(event);
        List<Callable<ResponseEntity<Map<String, Object>>>> requests = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            requests.add(() -> postEvent(event));
            if (i % 3 == 0) {
                requests.add(() -> postAsManagement("/api/v1/issues/" + issueId + "/resolve"));
            }
        }

        List<ResponseEntity<Map<String, Object>>> responses = runSimultaneously(requests);

        assertThat(responses).extracting(ResponseEntity::getStatusCode)
                .containsOnly(HttpStatus.CREATED, HttpStatus.OK, HttpStatus.CONFLICT);
        assertThat(responses).filteredOn(response -> response.getStatusCode() == HttpStatus.CREATED)
                .hasSize(CONCURRENT_REQUESTS);
        assertThat(jdbcTemplate.queryForObject(
                "select occurrence_count from issues where id = ?::uuid", Long.class, issueId))
                .isEqualTo(CONCURRENT_REQUESTS + 1);
        assertThat(countRows("events")).isEqualTo(CONCURRENT_REQUESTS + 1);
        assertThat(jdbcTemplate.queryForObject(
                "select (status = 'RESOLVED') = (resolved_at is not null) from issues where id = ?::uuid",
                Boolean.class, issueId)).isTrue();
    }
}
