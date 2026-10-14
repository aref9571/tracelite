package com.tracelite.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.tracelite.event.domain.ErrorEvent;
import com.tracelite.event.persistence.ErrorEventRepository;
import com.tracelite.security.TestApiKeys;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(PostgresTestcontainersConfiguration.class)
class EventIngestionRollbackTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private ErrorEventRepository errorEventRepository;

    @Test
    void aFailedEventInsertRollsBackTheOccurrenceCountAndReportsAnError() {
        jdbcTemplate.execute("TRUNCATE issues CASCADE");
        String event = EventIngestionIntegrationTests.event("production", "java.lang.IllegalStateException",
                Instant.now().minusSeconds(10));
        assertThat(post(event).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        doThrow(new DataIntegrityViolationException("simulated failure"))
                .when(errorEventRepository).save(any(ErrorEvent.class));
        ResponseEntity<String> response = post(event);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).doesNotContain("eventId", "issueId").contains("An unexpected error occurred.");
        assertThat(jdbcTemplate.queryForObject("select occurrence_count from issues", Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from events", Long.class)).isEqualTo(1);
    }

    private ResponseEntity<String> post(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(TestApiKeys.HEADER, TestApiKeys.CHECKOUT_INGESTION_KEY);
        return restTemplate.postForEntity("/api/v1/events", new HttpEntity<>(json, headers), String.class);
    }
}
