package com.tracelite.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.tracelite.event.domain.ErrorEvent;
import com.tracelite.event.persistence.ErrorEventRepository;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class EventIngestionRollbackTests extends ApiIntegrationTestSupport {

    @MockitoSpyBean
    private ErrorEventRepository errorEventRepository;

    @Test
    void aFailedEventInsertRollsBackTheOccurrenceCountAndReportsAnError() {
        String event = event("production", "java.lang.IllegalStateException", Instant.now().minusSeconds(10));
        ingest(event);

        doThrow(new DataIntegrityViolationException("simulated failure"))
                .when(errorEventRepository).save(any(ErrorEvent.class));
        ResponseEntity<Map<String, Object>> response = postEvent(event);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).doesNotContainKeys("eventId", "issueId")
                .containsEntry("detail", "An unexpected error occurred.");
        assertThat(jdbcTemplate.queryForObject("select occurrence_count from issues", Long.class)).isEqualTo(1);
        assertThat(countRows("events")).isEqualTo(1);
    }
}
