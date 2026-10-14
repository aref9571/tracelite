package com.tracelite.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.tracelite.security.TestApiKeys;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(PostgresTestcontainersConfiguration.class)
class HealthEndpointTests {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    private TestRestTemplate restTemplate;

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"})
    void healthEndpointsReportUp(String path) {
        ResponseEntity<Map<String, Object>> response = getJson(path);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
    }

    @Test
    void healthEndpointDoesNotExposeComponentDetails() {
        ResponseEntity<Map<String, Object>> response = getJson("/actuator/health");

        assertThat(response.getBody()).doesNotContainKeys("components", "details");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/env", "/actuator/configprops", "/actuator/beans", "/actuator/heapdump"})
    void sensitiveActuatorEndpointsAreNotReachable(String path) {
        ResponseEntity<String> anonymous = restTemplate.getForEntity(path, String.class);
        ResponseEntity<String> withManagementKey =
                restTemplate.exchange(path, HttpMethod.GET, TestApiKeys.managementRequest(), String.class);

        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(withManagementKey.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private ResponseEntity<Map<String, Object>> getJson(String path) {
        return restTemplate.exchange(path, HttpMethod.GET, null, JSON_OBJECT);
    }
}
