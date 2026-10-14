package com.tracelite.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-API-Key";

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);

    private final ApiKeyRegistry apiKeyRegistry;

    public ApiKeyAuthenticationFilter(ApiKeyRegistry apiKeyRegistry) {
        this.apiKeyRegistry = apiKeyRegistry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        List<String> presentedKeys = Collections.list(request.getHeaders(API_KEY_HEADER));
        if (!presentedKeys.isEmpty()) {
            Optional<Authentication> authentication = presentedKeys.size() == 1
                    ? apiKeyRegistry.authenticate(presentedKeys.getFirst())
                    : Optional.empty();
            authentication.ifPresentOrElse(
                    this::setAuthentication,
                    () -> log.debug("Rejected an invalid API key for {} {}", request.getMethod(), request.getRequestURI()));
        }
        chain.doFilter(request, response);
    }

    private void setAuthentication(Authentication authentication) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }
}
