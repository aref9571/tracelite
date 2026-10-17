package com.tracelite.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tracelite.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void generatesARequestIdWhenNoneIsPresented() throws Exception {
        MdcRecordingServlet servlet = new MdcRecordingServlet();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/issues"), response, new MockFilterChain(servlet));

        String requestId = response.getHeader(RequestIdFilter.REQUEST_ID_HEADER);
        assertThat(UUID.fromString(requestId)).isNotNull();
        assertThat(servlet.requestIdDuringRequest).isEqualTo(requestId);
    }

    @Test
    void keepsAValidPresentedRequestId() throws Exception {
        MdcRecordingServlet servlet = new MdcRecordingServlet();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/events");
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, "edge-7f3a_01.b");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain(servlet));

        assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).isEqualTo("edge-7f3a_01.b");
        assertThat(servlet.requestIdDuringRequest).isEqualTo("edge-7f3a_01.b");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "has space", "line\nbreak", "semi;colon", "{json}"})
    void replacesAnInvalidPresentedRequestId(String presented) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/issues");
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, presented);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain(new MdcRecordingServlet()));

        String requestId = response.getHeader(RequestIdFilter.REQUEST_ID_HEADER);
        assertThat(requestId).isNotEqualTo(presented);
        assertThat(UUID.fromString(requestId)).isNotNull();
    }

    @Test
    void replacesAPresentedRequestIdLongerThan64Characters() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/issues");
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, "a".repeat(65));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain(new MdcRecordingServlet()));

        assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).hasSize(36);
    }

    @Test
    void removesTheRequestIdFromTheLoggingContextAfterTheRequest() throws Exception {
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/issues"), new MockHttpServletResponse(),
                new MockFilterChain(new MdcRecordingServlet()));

        assertThat(MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY)).isNull();
    }

    @Test
    void removesTheRequestIdFromTheLoggingContextWhenTheRequestFails() {
        HttpServlet failingServlet = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) {
                throw new IllegalStateException("failed");
            }
        };

        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/issues"),
                new MockHttpServletResponse(), new MockFilterChain(failingServlet)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY)).isNull();
    }

    private static final class MdcRecordingServlet extends HttpServlet {

        private String requestIdDuringRequest;

        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) {
            requestIdDuringRequest = MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY);
        }
    }
}
