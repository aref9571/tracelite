package com.tracelite.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.tracelite.security.TestApiKeys;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
        "logging.level.com.tracelite=DEBUG", "logging.level.org.springframework.security=TRACE"})
@ExtendWith(OutputCaptureExtension.class)
class ApiSecurityIntegrationTests extends ApiIntegrationTestSupport {

    private static final String UNKNOWN_KEY = "unknown-key-0123456789abcdef0123456789";

    @Test
    void ingestionKeyStoresEventsAndManagementKeyReadsAndResolvesThem() {
        ResponseEntity<Map<String, Object>> ingested = postEvent("checkout-service", TestApiKeys.CHECKOUT_INGESTION_KEY);
        assertThat(ingested.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String issuePath = "/api/v1/issues/" + ingested.getBody().get("issueId");

        ResponseEntity<Map<String, Object>> issue = exchange(HttpMethod.GET, issuePath, TestApiKeys.MANAGEMENT_KEY);
        ResponseEntity<Map<String, Object>> resolved = exchange(HttpMethod.POST, issuePath + "/resolve", TestApiKeys.MANAGEMENT_KEY);

        assertThat(issue.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(issue.getBody()).containsEntry("application", "checkout-service");
        assertThat(resolved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resolved.getBody()).containsEntry("status", "RESOLVED");
    }

    @Test
    void rejectedIngestionRequestsStoreNothing() {
        assertThat(postEvent("checkout-service", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(postEvent("checkout-service", UNKNOWN_KEY).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(postEvent("checkout-service", TestApiKeys.MANAGEMENT_KEY).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(postEvent("checkout-service", TestApiKeys.BILLING_INGESTION_KEY).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertNothingStored();
    }

    @Test
    void anIngestionKeyCannotReadOrChangeIssues() {
        String issueId = (String) postEvent("billing-service", TestApiKeys.BILLING_INGESTION_KEY).getBody().get("issueId");

        assertThat(exchange(HttpMethod.GET, "/api/v1/issues", TestApiKeys.BILLING_INGESTION_KEY).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(HttpMethod.POST, "/api/v1/issues/" + issueId + "/resolve",
                TestApiKeys.BILLING_INGESTION_KEY).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbcTemplate.queryForObject("select status from issues", String.class)).isEqualTo("UNRESOLVED");
    }

    @Test
    void anUnauthenticatedRequestOverTheSizeLimitIsRejectedAsUnauthorized() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"message\":\"" + "x".repeat(300 * 1024) + "\"}";

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                EVENTS_PATH, HttpMethod.POST, new HttpEntity<>(body, headers), JSON_OBJECT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/health/readiness", "/v3/api-docs", "/swagger-ui/index.html"})
    void publicEndpointsNeedNoApiKey(String path) {
        ResponseEntity<String> response = restTemplate.getForEntity(path, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void theOpenApiDocumentDescribesTheApiKeyHeader() {
        ResponseEntity<Map<String, Object>> response =
                restTemplate.exchange("/v3/api-docs", HttpMethod.GET, null, JSON_OBJECT);

        assertThat(response.getBody().toString())
                .contains("apiKey")
                .contains("in=header")
                .contains("name=X-API-Key");
    }

    @Test
    void apiKeysNeverAppearInTheLogs(CapturedOutput output) {
        postEvent("checkout-service", TestApiKeys.CHECKOUT_INGESTION_KEY);
        postEvent("checkout-service", TestApiKeys.BILLING_INGESTION_KEY);
        postEvent("checkout-service", UNKNOWN_KEY);
        exchange(HttpMethod.GET, "/api/v1/issues", TestApiKeys.MANAGEMENT_KEY);
        exchange(HttpMethod.GET, "/api/v1/issues", UNKNOWN_KEY);

        assertThat(output).contains("Rejected an invalid API key for POST /api/v1/events");
        assertThat(output).doesNotContain(
                TestApiKeys.CHECKOUT_INGESTION_KEY,
                TestApiKeys.BILLING_INGESTION_KEY,
                TestApiKeys.MANAGEMENT_KEY,
                UNKNOWN_KEY);
    }

    private ResponseEntity<Map<String, Object>> postEvent(String application, String apiKey) {
        return postEvent(Map.of(
                "application", application,
                "environment", "production",
                "exceptionType", "java.lang.IllegalStateException",
                "message", "Cart is empty",
                "occurredAt", Instant.now().minusSeconds(5).toString()), apiKey);
    }
}
