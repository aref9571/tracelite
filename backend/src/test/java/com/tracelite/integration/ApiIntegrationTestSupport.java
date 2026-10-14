package com.tracelite.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.tracelite.security.TestApiKeys;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
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
abstract class ApiIntegrationTestSupport {

    static final String EVENTS_PATH = "/api/v1/events";
    static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void deleteStoredData() {
        jdbcTemplate.execute("TRUNCATE issues CASCADE");
    }

    ResponseEntity<Map<String, Object>> postEvent(Object event) {
        return postEvent(event, TestApiKeys.CHECKOUT_INGESTION_KEY);
    }

    ResponseEntity<Map<String, Object>> postEvent(Object event, String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null) {
            headers.set(TestApiKeys.HEADER, apiKey);
        }
        return restTemplate.exchange(EVENTS_PATH, HttpMethod.POST, new HttpEntity<>(event, headers), JSON_OBJECT);
    }

    String ingest(Object event) {
        ResponseEntity<Map<String, Object>> response = postEvent(event);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) response.getBody().get("issueId");
    }

    ResponseEntity<Map<String, Object>> exchange(HttpMethod method, String path, String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(TestApiKeys.HEADER, apiKey);
        return restTemplate.exchange(URI.create(path), method, new HttpEntity<>(headers), JSON_OBJECT);
    }

    ResponseEntity<Map<String, Object>> getAsManagement(String path) {
        return exchange(HttpMethod.GET, path, TestApiKeys.MANAGEMENT_KEY);
    }

    ResponseEntity<Map<String, Object>> postAsManagement(String path) {
        return exchange(HttpMethod.POST, path, TestApiKeys.MANAGEMENT_KEY);
    }

    long countRows(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
    }

    void assertNothingStored() {
        assertThat(countRows("issues")).isZero();
        assertThat(countRows("events")).isZero();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> items(ResponseEntity<Map<String, Object>> response) {
        return (List<Map<String, Object>>) response.getBody().get("items");
    }

    static <T> List<T> runSimultaneously(List<Callable<T>> requests) throws Exception {
        CountDownLatch ready = new CountDownLatch(requests.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(requests.size())) {
            for (Callable<T> request : requests) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return request.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    static <T> List<T> runSimultaneously(int count, Callable<T> request) throws Exception {
        return runSimultaneously(Collections.nCopies(count, request));
    }

    static String event(String environment, String exceptionType, Instant occurredAt) {
        return """
                {
                  "application": "checkout-service",
                  "environment": "%s",
                  "exceptionType": "%s",
                  "message": "Cart is empty",
                  "stackTrace": "%s: Cart is empty\\n\\tat com.example.Cart.checkout(Cart.java:42)",
                  "occurredAt": "%s",
                  "correlationId": "req-7f3a",
                  "context": {"orderId": "o-1001"}
                }
                """.formatted(environment, exceptionType, exceptionType, occurredAt);
    }
}
