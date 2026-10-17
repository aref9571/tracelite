package com.tracelite.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String REQUEST_ID_MDC_KEY = "requestId";

    private static final Pattern VALID_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final String API_PATH_PREFIX = "/api/";

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = requestIdFrom(request);
        long startNanos = System.nanoTime();
        MDC.put(REQUEST_ID_MDC_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            if (request.getRequestURI().startsWith(API_PATH_PREFIX)) {
                long durationMillis = (System.nanoTime() - startNanos) / 1_000_000;
                log.info("{} {} answered {} in {} ms",
                        request.getMethod(), request.getRequestURI(), response.getStatus(), durationMillis);
            }
            MDC.remove(REQUEST_ID_MDC_KEY);
        }
    }

    private static String requestIdFrom(HttpServletRequest request) {
        String presented = request.getHeader(REQUEST_ID_HEADER);
        if (presented != null && VALID_REQUEST_ID.matcher(presented).matches()) {
            return presented;
        }
        return UUID.randomUUID().toString();
    }
}
