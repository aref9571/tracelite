package com.tracelite.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class IssueApiIntegrationTests extends ApiIntegrationTestSupport {

    @Test
    void listsFiltersAndRetrievesIngestedIssues() {
        String paymentIssueId = ingest("production", "com.example.PaymentDeclinedException", "Card rejected");
        ingest("production", "com.example.PaymentDeclinedException", "Card rejected again");
        String stagingIssueId = ingest("staging", "java.lang.IllegalStateException", "Cart is empty");

        ResponseEntity<Map<String, Object>> all = getAsManagement("/api/v1/issues");
        assertThat(all.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(all.getBody()).containsEntry("totalItems", 2).containsEntry("totalPages", 1);
        assertThat(itemIds(all)).containsExactly(stagingIssueId, paymentIssueId);

        ResponseEntity<Map<String, Object>> filtered =
                getAsManagement("/api/v1/issues?environment=production&status=UNRESOLVED&query=payment");
        assertThat(itemIds(filtered)).containsExactly(paymentIssueId);
        assertThat(items(filtered).get(0)).containsEntry("occurrenceCount", 2).containsEntry("message", "Card rejected");

        ResponseEntity<Map<String, Object>> detail = getAsManagement("/api/v1/issues/" + paymentIssueId);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detail.getBody())
                .containsEntry("id", paymentIssueId)
                .containsEntry("environment", "production")
                .containsEntry("exceptionType", "com.example.PaymentDeclinedException")
                .containsEntry("status", "UNRESOLVED")
                .containsEntry("occurrenceCount", 2);
    }

    @Test
    void acceptsAnEncodedTimestampWithAnOffset() {
        ingest("production", "java.lang.IllegalStateException", "Cart is empty");

        ResponseEntity<Map<String, Object>> response =
                getAsManagement("/api/v1/issues?lastSeenFrom=2000-01-01T00:00:00%2B02:00&lastSeenTo=2999-01-01T00:00:00Z");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("totalItems", 1);
    }

    @Test
    void returnsNotFoundProblemDetailsForAnUnknownIssue() {
        ResponseEntity<Map<String, Object>> response = getAsManagement("/api/v1/issues/" + UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).containsEntry("title", "Issue not found").containsEntry("status", 404);
    }

    @Test
    void rejectsInvalidQueryParametersWithProblemDetails() {
        ResponseEntity<Map<String, Object>> response = getAsManagement("/api/v1/issues?page=-1&size=1000");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("title", "Invalid request");
        assertThat(response.getBody().get("errors")).asList().hasSize(2);
    }

    @Test
    void publishesTheIssueEndpointsInTheOpenApiDocument() {
        ResponseEntity<Map<String, Object>> response = getAsManagement("/v3/api-docs");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> paths = (Map<String, Object>) response.getBody().get("paths");
        assertThat(paths).containsKeys("/api/v1/events", "/api/v1/issues", "/api/v1/issues/{id}");
    }

    private String ingest(String environment, String exceptionType, String message) {
        return ingest(Map.of(
                "application", "checkout-service",
                "environment", environment,
                "exceptionType", exceptionType,
                "message", message,
                "occurredAt", Instant.now().minusSeconds(5).toString()));
    }

    private static List<String> itemIds(ResponseEntity<Map<String, Object>> response) {
        return items(response).stream().map(item -> (String) item.get("id")).toList();
    }
}
