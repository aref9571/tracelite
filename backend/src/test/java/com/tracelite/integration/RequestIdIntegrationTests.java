package com.tracelite.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.tracelite.common.web.RequestIdFilter;
import com.tracelite.security.TestApiKeys;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@ExtendWith(OutputCaptureExtension.class)
class RequestIdIntegrationTests extends ApiIntegrationTestSupport {

    @Test
    void acceptedRequestsReturnAndLogTheirRequestId(CapturedOutput output) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(TestApiKeys.HEADER, TestApiKeys.CHECKOUT_INGESTION_KEY);
        headers.set(RequestIdFilter.REQUEST_ID_HEADER, "client-request-42");
        String event = event("production", "java.lang.IllegalStateException", Instant.now().minusSeconds(10));

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                EVENTS_PATH, HttpMethod.POST, new HttpEntity<>(event, headers), JSON_OBJECT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getFirst(RequestIdFilter.REQUEST_ID_HEADER)).isEqualTo("client-request-42");
        assertThat(output).contains("[client-request-42] ")
                .contains("POST /api/v1/events answered 201 in ");
    }

    @Test
    void rejectedRequestsAlsoGetARequestId(CapturedOutput output) {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                URI.create("/api/v1/issues"), HttpMethod.GET, HttpEntity.EMPTY, JSON_OBJECT);

        String requestId = response.getHeaders().getFirst(RequestIdFilter.REQUEST_ID_HEADER);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(requestId).isNotBlank();
        assertThat(output).contains("[" + requestId + "] ")
                .contains("GET /api/v1/issues answered 401 in ");
    }

    @Test
    void healthChecksAreNotLoggedPerRequest(CapturedOutput output) {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                URI.create("/actuator/health"), HttpMethod.GET, HttpEntity.EMPTY, JSON_OBJECT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(RequestIdFilter.REQUEST_ID_HEADER)).isNotBlank();
        assertThat(output).doesNotContain("/actuator/health answered");
    }
}
