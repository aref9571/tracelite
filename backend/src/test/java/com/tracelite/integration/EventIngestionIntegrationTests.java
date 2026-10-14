package com.tracelite.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.tracelite.security.TestApiKeys;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
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
class EventIngestionIntegrationTests {

    private static final String EVENTS_PATH = "/api/v1/events";
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @LocalServerPort
    private int port;

    @BeforeEach
    void deleteStoredData() {
        jdbcTemplate.execute("TRUNCATE issues CASCADE");
    }

    @Test
    void storesAValidEventAndCreatesAnIssue() {
        Instant occurredAt = Instant.now().minusSeconds(10);

        ResponseEntity<Map<String, Object>> response = postEvent(event("production", "java.lang.IllegalStateException", occurredAt));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsKeys("eventId", "issueId", "receivedAt");

        Map<String, Object> issue = jdbcTemplate.queryForMap(
                "select application, environment, exception_type, status, occurrence_count from issues where id = ?::uuid",
                body.get("issueId"));
        assertThat(issue).containsEntry("application", "checkout-service")
                .containsEntry("environment", "production")
                .containsEntry("exception_type", "java.lang.IllegalStateException")
                .containsEntry("status", "UNRESOLVED")
                .containsEntry("occurrence_count", 1L);

        Map<String, Object> event = jdbcTemplate.queryForMap(
                "select issue_id::text as issue_id, occurred_at, received_at, correlation_id, "
                        + "context ->> 'orderId' as order_id "
                        + "from events where id = ?::uuid",
                body.get("eventId"));
        assertThat(event).containsEntry("issue_id", body.get("issueId"))
                .containsEntry("correlation_id", "req-7f3a")
                .containsEntry("order_id", "o-1001");
        assertThat(((Timestamp) event.get("occurred_at")).toInstant()).isEqualTo(occurredAt.truncatedTo(ChronoUnit.MICROS));
        assertThat(((Timestamp) event.get("received_at")).toInstant()).isEqualTo(Instant.parse((String) body.get("receivedAt")));
    }

    @Test
    void groupsRepeatedEventsIntoOneIssue() {
        Instant occurredAt = Instant.now().minusSeconds(10);

        Object firstIssueId = postEvent(event("production", "java.lang.IllegalStateException", occurredAt)).getBody().get("issueId");
        Object secondIssueId = postEvent(event("production", "java.lang.IllegalStateException", occurredAt)).getBody().get("issueId");

        assertThat(secondIssueId).isEqualTo(firstIssueId);
        assertThat(jdbcTemplate.queryForObject("select occurrence_count from issues where id = ?::uuid", Long.class, firstIssueId))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select count(*) from events", Long.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "select last_seen_at = (select max(received_at) from events) from issues", Boolean.class)).isTrue();
    }

    @Test
    void keepsIssuesSeparateByEnvironmentAndExceptionType() {
        Instant occurredAt = Instant.now().minusSeconds(10);

        postEvent(event("production", "java.lang.IllegalStateException", occurredAt));
        postEvent(event("staging", "java.lang.IllegalStateException", occurredAt));
        postEvent(event("production", "java.lang.NullPointerException", occurredAt));

        assertThat(jdbcTemplate.queryForObject("select count(*) from issues", Long.class)).isEqualTo(3);
    }

    @Test
    void groupsEventsWhoseStackTracesDifferOnlyInLineNumbersAndMessages() {
        Instant occurredAt = Instant.now().minusSeconds(10);
        String first = event("production", "java.lang.IllegalStateException", occurredAt);
        String second = first.replace("Cart.java:42", "Cart.java:57").replace("Cart is empty", "Cart 9 is empty");

        Object firstIssueId = postEvent(first).getBody().get("issueId");
        Object secondIssueId = postEvent(second).getBody().get("issueId");

        assertThat(secondIssueId).isEqualTo(firstIssueId);
        assertThat(jdbcTemplate.queryForObject("select message from issues", String.class)).isEqualTo("Cart is empty");
    }

    @Test
    void keepsIssuesSeparateWhenTheThrowingMethodDiffers() {
        Instant occurredAt = Instant.now().minusSeconds(10);
        String checkout = event("production", "java.lang.IllegalStateException", occurredAt);
        String addItem = checkout.replace("Cart.checkout", "Cart.addItem");

        Object checkoutIssueId = postEvent(checkout).getBody().get("issueId");
        Object addItemIssueId = postEvent(addItem).getBody().get("issueId");

        assertThat(addItemIssueId).isNotEqualTo(checkoutIssueId);
        assertThat(jdbcTemplate.queryForObject("select count(*) from issues", Long.class)).isEqualTo(2);
    }

    @Test
    void groupsEventsWithoutAStackTraceByExceptionType() {
        Instant occurredAt = Instant.now().minusSeconds(10);
        String withoutTrace = event("production", "java.lang.IllegalStateException", occurredAt)
                .replaceAll("\"stackTrace\": \".*\",", "");

        Object firstIssueId = postEvent(withoutTrace).getBody().get("issueId");
        Object secondIssueId = postEvent(withoutTrace).getBody().get("issueId");

        assertThat(withoutTrace).doesNotContain("stackTrace");
        assertThat(secondIssueId).isEqualTo(firstIssueId);
    }

    @Test
    void aNewEventReopensAResolvedIssue() {
        Instant occurredAt = Instant.now().minusSeconds(10);
        String event = event("production", "java.lang.IllegalStateException", occurredAt);
        Object issueId = postEvent(event).getBody().get("issueId");
        jdbcTemplate.update("update issues set status = 'RESOLVED', resolved_at = now() where id = ?::uuid", issueId);

        ResponseEntity<Map<String, Object>> response = postEvent(event);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("issueId")).isEqualTo(issueId);
        Map<String, Object> issue = jdbcTemplate.queryForMap(
                "select status, resolved_at, occurrence_count from issues where id = ?::uuid", issueId);
        assertThat(issue).containsEntry("status", "UNRESOLVED")
                .containsEntry("occurrence_count", 2L);
        assertThat(issue.get("resolved_at")).isNull();
    }

    @Test
    void rejectsAnInvalidEventWithoutStoringAnything() {
        ResponseEntity<Map<String, Object>> response = postEvent("{\"application\": \"checkout-service\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertNothingStored();
    }

    @Test
    void rejectsAnEventFromTheFarFuture() {
        ResponseEntity<Map<String, Object>> response =
                postEvent(event("production", "java.lang.IllegalStateException", Instant.now().plusSeconds(3600)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("errors").toString()).contains("occurredAt");
        assertNothingStored();
    }

    @Test
    void rejectsNullCharactersThatPostgresCannotStore() {
        String body = event("production", "java.lang.IllegalStateException", Instant.now())
                .replace("\"o-1001\"", "\"o-\\u0000-1001\"");

        ResponseEntity<Map<String, Object>> response = postEvent(body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("errors").toString()).contains("context");
        assertNothingStored();
    }

    @Test
    void rejectsAChunkedBodyOverTheSizeLimit() throws Exception {
        String body = event("production", "java.lang.IllegalStateException", Instant.now())
                .replace("\"Cart is empty\"", "\"" + "x".repeat(300 * 1024) + "\"");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + EVENTS_PATH))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .header(TestApiKeys.HEADER, TestApiKeys.CHECKOUT_INGESTION_KEY)
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes)))
                .build();

        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        }

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("Request too large");
        assertNothingStored();
    }

    private void assertNothingStored() {
        assertThat(jdbcTemplate.queryForObject("select count(*) from issues", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from events", Long.class)).isZero();
    }

    private ResponseEntity<Map<String, Object>> postEvent(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(TestApiKeys.HEADER, TestApiKeys.CHECKOUT_INGESTION_KEY);
        return restTemplate.exchange(EVENTS_PATH, HttpMethod.POST, new HttpEntity<>(json, headers), JSON_OBJECT);
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
