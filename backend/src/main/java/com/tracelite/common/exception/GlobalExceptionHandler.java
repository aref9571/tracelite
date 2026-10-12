package com.tracelite.common.exception;

import com.tracelite.common.web.FieldViolation;
import com.tracelite.common.web.RequestSizeLimitFilter;
import com.tracelite.common.web.RequestTooLargeException;
import com.tracelite.event.application.InvalidErrorEventException;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String ERRORS_PROPERTY = "errors";

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        List<FieldViolation> violations = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
                .sorted(Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::message))
                .toList();
        return ResponseEntity.badRequest().body(invalidRequestProblem(violations));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        if (exception.getMostSpecificCause() instanceof RequestTooLargeException tooLarge) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(RequestSizeLimitFilter.tooLargeProblem(tooLarge.getMaxBytes()));
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "The request body is missing, is not valid JSON, or has a field of the wrong type.");
        problem.setTitle("Malformed request");
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(InvalidErrorEventException.class)
    ProblemDetail handleInvalidErrorEvent(InvalidErrorEventException exception) {
        return invalidRequestProblem(List.of(new FieldViolation(exception.getField(), exception.getReason())));
    }

    @ExceptionHandler({
            CannotCreateTransactionException.class,
            DataAccessResourceFailureException.class,
            TransientDataAccessException.class})
    ProblemDetail handleDatabaseUnavailable(Exception exception) {
        log.warn("Database unavailable while handling a request", exception);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "The service is temporarily unavailable. Try again later.");
        problem.setTitle("Service unavailable");
        return problem;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception exception) {
        log.error("Unexpected error while handling a request", exception);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");
        problem.setTitle("Internal server error");
        return problem;
    }

    private static ProblemDetail invalidRequestProblem(List<FieldViolation> violations) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "The request contains invalid fields.");
        problem.setTitle("Invalid request");
        problem.setProperty(ERRORS_PROPERTY, violations);
        return problem;
    }
}
