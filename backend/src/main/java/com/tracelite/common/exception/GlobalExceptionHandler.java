package com.tracelite.common.exception;

import com.tracelite.common.web.FieldViolation;
import com.tracelite.common.web.RequestSizeLimitFilter;
import com.tracelite.common.web.RequestTooLargeException;
import com.tracelite.event.application.InvalidErrorEventException;
import com.tracelite.issue.application.InvalidIssueQueryException;
import com.tracelite.issue.application.IssueNotFoundException;
import com.tracelite.issue.domain.InvalidIssueStatusTransitionException;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String ERRORS_PROPERTY = "errors";
    private static final String INVALID_VALUE_MESSAGE = "has an invalid value";

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        List<FieldViolation> violations = exception.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::toFieldViolation)
                .sorted(Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::message))
                .toList();
        return ResponseEntity.badRequest().body(invalidRequestProblem(violations));
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        FieldViolation violation = new FieldViolation(exception.getPropertyName(), INVALID_VALUE_MESSAGE);
        return ResponseEntity.badRequest().body(invalidRequestProblem(List.of(violation)));
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

    @ExceptionHandler(InvalidIssueQueryException.class)
    ProblemDetail handleInvalidIssueQuery(InvalidIssueQueryException exception) {
        return invalidRequestProblem(List.of(new FieldViolation(exception.getField(), exception.getReason())));
    }

    @ExceptionHandler(IssueNotFoundException.class)
    ProblemDetail handleIssueNotFound(IssueNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No issue exists with this ID.");
        problem.setTitle("Issue not found");
        return problem;
    }

    @ExceptionHandler(InvalidIssueStatusTransitionException.class)
    ProblemDetail handleInvalidStatusTransition(InvalidIssueStatusTransitionException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "The issue status cannot change from " + exception.getCurrentStatus()
                        + " to " + exception.getTargetStatus() + ".");
        problem.setTitle("Invalid status transition");
        problem.setProperty("currentStatus", exception.getCurrentStatus());
        problem.setProperty("requestedStatus", exception.getTargetStatus());
        return problem;
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail handleConcurrentModification(OptimisticLockingFailureException exception) {
        log.info("Rejected a change to a resource that another request modified first");
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "The resource was changed by another request. Fetch it again and retry.");
        problem.setTitle("Concurrent modification");
        return problem;
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

    private static FieldViolation toFieldViolation(FieldError error) {
        String message = error.isBindingFailure() ? INVALID_VALUE_MESSAGE : error.getDefaultMessage();
        return new FieldViolation(error.getField(), message);
    }

    private static ProblemDetail invalidRequestProblem(List<FieldViolation> violations) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "The request contains invalid fields.");
        problem.setTitle("Invalid request");
        problem.setProperty(ERRORS_PROPERTY, violations);
        return problem;
    }
}
