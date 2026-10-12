package com.tracelite.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracelite.config.IngestionProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private static final String API_PATH_PREFIX = "/api/";

    private final long maxRequestBytes;
    private final ObjectMapper objectMapper;

    public RequestSizeLimitFilter(IngestionProperties properties, ObjectMapper objectMapper) {
        this.maxRequestBytes = properties.maxRequestSize().toBytes();
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(API_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxRequestBytes) {
            writeTooLargeResponse(response);
            return;
        }
        chain.doFilter(new SizeLimitedRequest(request, maxRequestBytes), response);
    }

    private void writeTooLargeResponse(HttpServletResponse response) throws IOException {
        ProblemDetail problem = tooLargeProblem(maxRequestBytes);
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Connection", "close");
        objectMapper.writeValue(response.getOutputStream(), problem);
    }

    public static ProblemDetail tooLargeProblem(long maxRequestBytes) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.PAYLOAD_TOO_LARGE, "The request body must not exceed " + maxRequestBytes + " bytes.");
        problem.setTitle("Request too large");
        return problem;
    }

    private static final class SizeLimitedRequest extends HttpServletRequestWrapper {

        private final long maxBytes;
        private ServletInputStream inputStream;

        SizeLimitedRequest(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (inputStream == null) {
                inputStream = new SizeLimitedInputStream(super.getInputStream(), maxBytes);
            }
            return inputStream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class SizeLimitedInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long maxBytes;
        private long bytesRead;

        SizeLimitedInputStream(ServletInputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value != -1) {
                countBytes(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = delegate.read(buffer, offset, length);
            if (count > 0) {
                countBytes(count);
            }
            return count;
        }

        private void countBytes(int count) throws RequestTooLargeException {
            bytesRead += count;
            if (bytesRead > maxBytes) {
                throw new RequestTooLargeException(maxBytes);
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }
    }
}
