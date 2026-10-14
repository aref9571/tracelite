package com.tracelite.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

public class SecurityProblemResponder implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final String AUTHENTICATE_CHALLENGE =
            "ApiKey header=\"" + ApiKeyAuthenticationFilter.API_KEY_HEADER + "\"";

    private final ObjectMapper objectMapper;

    public SecurityProblemResponder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, AUTHENTICATE_CHALLENGE);
        write(request, response, unauthorizedProblem());
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        write(request, response, forbiddenProblem("The API key does not permit this request."));
    }

    public static ProblemDetail unauthorizedProblem() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED,
                "A valid API key is required in the " + ApiKeyAuthenticationFilter.API_KEY_HEADER + " header.");
        problem.setTitle("Unauthorized");
        return problem;
    }

    public static ProblemDetail forbiddenProblem(String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, detail);
        problem.setTitle("Forbidden");
        return problem;
    }

    private void write(HttpServletRequest request, HttpServletResponse response, ProblemDetail problem)
            throws IOException {
        problem.setInstance(URI.create(request.getRequestURI()));
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
