package com.tracelite.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracelite.common.web.RequestSizeLimitFilter;
import com.tracelite.common.web.RequestTooLargeException;
import com.tracelite.config.IngestionProperties;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;

class RequestSizeLimitFilterTest {

    private static final int MAX_BYTES = 100;

    private final RequestSizeLimitFilter filter = new RequestSizeLimitFilter(
            new IngestionProperties(DataSize.ofBytes(MAX_BYTES), Duration.ofMinutes(5), Duration.ofDays(30)),
            new ObjectMapper());

    @Test
    void passesABodyWithinTheLimitThrough() throws Exception {
        BodyReadingServlet servlet = new BodyReadingServlet();

        filter.doFilter(chunkedRequest("/api/v1/events", MAX_BYTES), new MockHttpServletResponse(),
                new MockFilterChain(servlet));

        assertThat(servlet.bytesRead).isEqualTo(MAX_BYTES);
    }

    @Test
    void stopsReadingABodyWithoutContentLengthOnceItExceedsTheLimit() {
        assertThatThrownBy(() -> filter.doFilter(chunkedRequest("/api/v1/events", MAX_BYTES + 1),
                new MockHttpServletResponse(), new MockFilterChain(new BodyReadingServlet())))
                .isInstanceOf(RequestTooLargeException.class);
    }

    @Test
    void rejectsADeclaredContentLengthOverTheLimitBeforeReading() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/events");
        request.setContent(new byte[MAX_BYTES + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getContentAsString()).contains("\"title\":\"Request too large\"");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void ignoresRequestsOutsideTheApi() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/actuator/health");
        request.setContent(new byte[MAX_BYTES + 1]);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    private static MockHttpServletRequest chunkedRequest(String path, int bodyLength) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path) {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent(new byte[bodyLength]);
        return request;
    }

    private static final class BodyReadingServlet extends HttpServlet {

        private int bytesRead;

        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
            bytesRead = request.getInputStream().readAllBytes().length;
        }
    }
}
