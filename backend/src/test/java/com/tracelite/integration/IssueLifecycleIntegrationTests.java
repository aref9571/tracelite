package com.tracelite.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(PostgresTestcontainersConfiguration.class)
class IssueLifecycleIntegrationTests {

    private static final int CONCURRENT_REQUESTS = 8;
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void deleteStoredData() {
        jdbcTemplate.execute("TRUNCATE issues CASCADE");
    }

    @Test
    void resolvesAndReopensAnIssueAndRejectsRepeatedRequests() {
        String issueId = ingest("Cart is empty");

        ResponseEntity<Map<String, Object>> resolved = post("/api/v1/issues/" + issueId + "/resolve");
        assertThat(resolved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resolved.getBody()).containsEntry("id", issueId).containsEntry("status", "RESOLVED");
        Timestamp resolvedAt = jdbcTemplate.queryForObject(
                "select resolved_at from issues where id = ?::uuid", Timestamp.class, issueId);
        assertThat(resolvedAt).isNotNull();
        assertThat(Instant.parse((String) resolved.getBody().get("resolvedAt"))).isEqualTo(resolvedAt.toInstant());
        assertThat(getJson("/api/v1/issues/" + issueId).getBody()).containsEntry("status", "RESOLVED");

        ResponseEntity<Map<String, Object>> resolvedAgain = post("/api/v1/issues/" + issueId + "/resolve");
        assertThat(resolvedAgain.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resolvedAgain.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(resolvedAgain.getBody())
                .containsEntry("title", "Invalid status transition")
                .containsEntry("currentStatus", "RESOLVED")
                .containsEntry("requestedStatus", "RESOLVED");
        assertThat(jdbcTemplate.queryForObject(
                "select resolved_at from issues where id = ?::uuid", Timestamp.class, issueId)).isEqualTo(resolvedAt);
        assertThat(versionOf(issueId)).isEqualTo(1);

        ResponseEntity<Map<String, Object>> reopened = post("/api/v1/issues/" + issueId + "/reopen");
        assertThat(reopened.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reopened.getBody()).containsEntry("status", "UNRESOLVED").containsEntry("resolvedAt", null);

        ResponseEntity<Map<String, Object>> reopenedAgain = post("/api/v1/issues/" + issueId + "/reopen");
        assertThat(reopenedAgain.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(reopenedAgain.getBody()).containsEntry("currentStatus", "UNRESOLVED");
        assertThat(versionOf(issueId)).isEqualTo(2);
    }

    @Test
    void aNewEventReopensAResolvedIssue() {
        String issueId = ingest("Cart is empty");
        assertThat(post("/api/v1/issues/" + issueId + "/resolve").getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(ingest("Cart is empty")).isEqualTo(issueId);

        assertThat(getJson("/api/v1/issues/" + issueId).getBody())
                .containsEntry("status", "UNRESOLVED")
                .containsEntry("resolvedAt", null)
                .containsEntry("occurrenceCount", 2);
        assertThat(post("/api/v1/issues/" + issueId + "/resolve").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void returnsTheEventHistoryNewestFirstInPages() {
        String issueId = ingest("first");
        ingest("second");
        ingest("third");
        List<String> storedNewestFirst = jdbcTemplate.queryForList(
                "select id::text from events where issue_id = ?::uuid order by received_at desc, id desc",
                String.class, issueId);

        ResponseEntity<Map<String, Object>> firstPage = getJson("/api/v1/issues/" + issueId + "/events?size=2");
        ResponseEntity<Map<String, Object>> secondPage =
                getJson("/api/v1/issues/" + issueId + "/events?size=2&page=1");

        assertThat(firstPage.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(firstPage.getBody()).containsEntry("totalItems", 3).containsEntry("totalPages", 2);
        assertThat(items(firstPage)).extracting(item -> item.get("message")).containsExactly("third", "second");
        List<Object> pagedIds = new ArrayList<>();
        items(firstPage).forEach(item -> pagedIds.add(item.get("id")));
        items(secondPage).forEach(item -> pagedIds.add(item.get("id")));
        assertThat(pagedIds).containsExactlyElementsOf(storedNewestFirst);
        assertThat(items(firstPage).get(0))
                .containsEntry("stackTrace",
                        "java.lang.IllegalStateException: third\n\tat com.example.Cart.checkout(Cart.java:42)")
                .containsKeys("occurredAt", "receivedAt");
    }

    @Test
    void returnsStatisticsThatMatchThePersistedData() {
        String issueId = ingest("Cart is empty");
        ingest("Cart is empty");
        ingest("Cart is empty");
        ingest("Another issue", "com.example.OtherException");

        ResponseEntity<Map<String, Object>> response = getJson("/api/v1/issues/" + issueId + "/stats");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> statistics = response.getBody();
        long storedEvents = jdbcTemplate.queryForObject(
                "select count(*) from events where issue_id = ?::uuid", Long.class, issueId);
        long occurrenceCount = jdbcTemplate.queryForObject(
                "select occurrence_count from issues where id = ?::uuid", Long.class, issueId);
        Timestamp lastSeenAt = jdbcTemplate.queryForObject(
                "select last_seen_at from issues where id = ?::uuid", Timestamp.class, issueId);
        assertThat(storedEvents).isEqualTo(3);
        assertThat(statistics)
                .containsEntry("issueId", issueId)
                .containsEntry("occurrenceCount", (int) occurrenceCount)
                .containsEntry("storedEventCount", (int) storedEvents)
                .containsEntry("eventsLast24Hours", 3)
                .containsEntry("eventsLast7Days", 3);
        assertThat(Instant.parse((String) statistics.get("lastSeenAt"))).isEqualTo(lastSeenAt.toInstant());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> daily = (List<Map<String, Object>>) statistics.get("dailyEventCounts");
        assertThat(daily).hasSize(14);
        assertThat(daily.stream().mapToInt(day -> (Integer) day.get("count")).sum()).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"events", "stats"})
    void returnsNotFoundForTheHistoryOfAnUnknownIssue(String resource) {
        ResponseEntity<Map<String, Object>> response = getJson("/api/v1/issues/" + UUID.randomUUID() + "/" + resource);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("title", "Issue not found");
    }

    @ParameterizedTest
    @ValueSource(strings = {"resolve", "reopen"})
    void returnsNotFoundWhenChangingAnUnknownIssue(String action) {
        ResponseEntity<Map<String, Object>> response = post("/api/v1/issues/" + UUID.randomUUID() + "/" + action);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("title", "Issue not found");
    }

    @Test
    void simultaneousResolveRequestsChangeTheIssueExactlyOnce() throws Exception {
        String issueId = ingest("Cart is empty");

        List<ResponseEntity<Map<String, Object>>> responses =
                postSimultaneously("/api/v1/issues/" + issueId + "/resolve", CONCURRENT_REQUESTS);

        assertThat(responses).filteredOn(response -> response.getStatusCode() == HttpStatus.OK).hasSize(1);
        assertThat(responses).filteredOn(response -> response.getStatusCode() == HttpStatus.CONFLICT)
                .hasSize(CONCURRENT_REQUESTS - 1);
        assertThat(jdbcTemplate.queryForObject(
                "select status from issues where id = ?::uuid", String.class, issueId)).isEqualTo("RESOLVED");
        assertThat(versionOf(issueId)).isEqualTo(1);
    }

    @Test
    void publishesTheHistoryAndLifecycleEndpointsInTheOpenApiDocument() {
        ResponseEntity<Map<String, Object>> response = getJson("/v3/api-docs");

        @SuppressWarnings("unchecked")
        Map<String, Object> paths = (Map<String, Object>) response.getBody().get("paths");
        assertThat(paths).containsKeys(
                "/api/v1/issues/{id}/events",
                "/api/v1/issues/{id}/stats",
                "/api/v1/issues/{id}/resolve",
                "/api/v1/issues/{id}/reopen");
    }

    private long versionOf(String issueId) {
        return jdbcTemplate.queryForObject("select version from issues where id = ?::uuid", Long.class, issueId);
    }

    private String ingest(String message) {
        return ingest(message, "java.lang.IllegalStateException");
    }

    private String ingest(String message, String exceptionType) {
        Map<String, Object> event = Map.of(
                "application", "checkout-service",
                "environment", "production",
                "exceptionType", exceptionType,
                "message", message,
                "stackTrace", exceptionType + ": " + message + "\n\tat com.example.Cart.checkout(Cart.java:42)",
                "occurredAt", Instant.now().minusSeconds(5).toString());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/events", HttpMethod.POST, new HttpEntity<>(event, headers), JSON_OBJECT);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) response.getBody().get("issueId");
    }

    private List<ResponseEntity<Map<String, Object>>> postSimultaneously(String path, int count) throws Exception {
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<Map<String, Object>>>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(count)) {
            for (int i = 0; i < count; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return post(path);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<ResponseEntity<Map<String, Object>>> responses = new ArrayList<>();
            for (Future<ResponseEntity<Map<String, Object>>> future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }
            return responses;
        }
    }

    private ResponseEntity<Map<String, Object>> post(String path) {
        return restTemplate.exchange(URI.create(path), HttpMethod.POST, null, JSON_OBJECT);
    }

    private ResponseEntity<Map<String, Object>> getJson(String path) {
        return restTemplate.exchange(URI.create(path), HttpMethod.GET, null, JSON_OBJECT);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(ResponseEntity<Map<String, Object>> response) {
        return (List<Map<String, Object>>) response.getBody().get("items");
    }
}
