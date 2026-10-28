package com.practice.cashflow.adapter.in.web;

import com.practice.cashflow.application.IdempotencyConflictException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Safe HTTP errors for the Part 4 payment acceptance and status endpoints. */
@Order(-1)
@RestControllerAdvice(assignableTypes = PaymentSubmissionController.class)
public class PaymentExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(PaymentExceptionHandler.class);

    public record FieldError(String field, String message) {}

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception failure, HttpServletRequest request) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred. Contact support with the request ID.", List.of(), request, failure);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception failure, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var servletRequest = ((ServletWebRequest) request).getRequest();
        var response = problem(status, status.is4xxClientError() ? "INVALID_REQUEST" : "INTERNAL_ERROR",
                status.is4xxClientError() ? "The payment request could not be accepted."
                        : "An unexpected error occurred. Contact support with the request ID.",
                List.of(), servletRequest, failure);
        // Preserve protocol headers such as Allow for a 405 response.
        var merged = new HttpHeaders();
        merged.putAll(headers);
        merged.putAll(response.getHeaders());
        return new ResponseEntity<>(response.getBody(), merged, status);
    }

    private ResponseEntity<Object> problem(HttpStatusCode status, String code, String detail,
            List<FieldError> errors, HttpServletRequest request, Exception failure) {
        String requestId = UUID.randomUUID().toString();
        var body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setType(URI.create("about:blank"));
        body.setInstance(URI.create(request.getRequestURI()));
        body.setProperty("code", code);
        body.setProperty("requestId", requestId);
        if (!errors.isEmpty()) {
            body.setProperty("errors", errors);
        }
        // Deliberately omit request values, SQL and exception messages from these logs.
        log.warn("Payment request failed requestId={} code={} status={} exception={}",
                requestId, code, status.value(), failure.getClass().getSimpleName());
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header("X-Request-ID", requestId)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<Object> idempotencyConflict(
            IdempotencyConflictException failure,
            HttpServletRequest request
    ) {
        return problem(
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_CONFLICT",
                "This key was already used with different payment details.",
                List.of(),
                request,
                failure
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Object> invalidSubmission(
            IllegalArgumentException failure,
            HttpServletRequest request
    ) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST",
                "One or more payment request fields are invalid.",
                List.of(),
                request,
                failure
        );
    }

    @ExceptionHandler({
            DataAccessResourceFailureException.class,
            TransientDataAccessException.class,
            CannotCreateTransactionException.class,
            TransactionSystemException.class
    })
    ResponseEntity<Object> storageUnavailable(
            RuntimeException failure,
            HttpServletRequest request
    ) {
        return problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "PAYMENT_STORAGE_UNAVAILABLE",
                "The outcome could not be confirmed. Retry with the same idempotency key.",
                List.of(),
                request,
                failure
        );
    }


}
