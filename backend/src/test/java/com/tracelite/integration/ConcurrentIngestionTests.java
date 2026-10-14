package com.tracelite.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.tracelite.security.TestApiKeys;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
class ConcurrentIngestionTests {

    private static final int CONCURRENT_REQUESTS = 12;
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
    void simultaneousFirstEventsForOneFingerprintCreateExactlyOneIssue() throws Exception {
        String event = EventIngestionIntegrationTests.event("production", "java.lang.IllegalStateException",
                Instant.now().minusSeconds(10));

        List<ResponseEntity<Map<String, Object>>> responses = postSimultaneously(event, CONCURRENT_REQUESTS);

        assertThat(responses).allSatisfy(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED));
        assertThat(responses).extracting(response -> response.getBody().get("issueId")).containsOnly(
                responses.getFirst().getBody().get("issueId"));
        assertThat(jdbcTemplate.queryForObject("select count(*) from issues", Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select occurrence_count from issues", Long.class))
                .isEqualTo(CONCURRENT_REQUESTS);
        assertThat(jdbcTemplate.queryForObject("select count(*) from events", Long.class))
                .isEqualTo(CONCURRENT_REQUESTS);
    }

    @Test
    void simultaneousEventsForAnExistingIssueAreAllCounted() throws Exception {
        String event = EventIngestionIntegrationTests.event("production", "java.lang.IllegalStateException",
                Instant.now().minusSeconds(10));
        assertThat(post(event).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        List<ResponseEntity<Map<String, Object>>> responses = postSimultaneously(event, CONCURRENT_REQUESTS);

        assertThat(responses).allSatisfy(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED));
        assertThat(jdbcTemplate.queryForObject("select count(*) from issues", Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select occurrence_count from issues", Long.class))
                .isEqualTo(CONCURRENT_REQUESTS + 1);
        assertThat(jdbcTemplate.queryForObject(
                "select last_seen_at = (select max(received_at) from events) from issues", Boolean.class)).isTrue();
    }

    private List<ResponseEntity<Map<String, Object>>> postSimultaneously(String event, int count) throws Exception {
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<Map<String, Object>>>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(count)) {
            for (int i = 0; i < count; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return post(event);
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

    private ResponseEntity<Map<String, Object>> post(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(TestApiKeys.HEADER, TestApiKeys.CHECKOUT_INGESTION_KEY);
        return restTemplate.exchange("/api/v1/events", HttpMethod.POST, new HttpEntity<>(json, headers), JSON_OBJECT);
    }
}
