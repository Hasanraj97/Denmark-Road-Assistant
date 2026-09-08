package com.example.backend.exception;

import com.example.backend.dto.ApiErrorResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.UUID;

/**
 * Turns exceptions into the JSON error body the frontend shows to the user.
 *
 * <p>Two things were wrong here before, and both made the app very hard to use.</p>
 *
 * <p><strong>1. Helpful messages were being thrown away.</strong> The class had a
 * catch-all {@code @ExceptionHandler(Exception.class)}. A
 * {@link ResponseStatusException} is an {@code Exception}, and no handler was more
 * specific, so the catch-all won. Every carefully worded message the code raised -
 * "The synthetic ML service is unavailable. Start the Python ml-service on port
 * 8000", "Could not match that place in Denmark" - was replaced by a flat HTTP 500
 * "Something went wrong on the server." The user was told nothing.</p>
 *
 * <p><strong>2. Nothing was logged.</strong> The catch-all discarded the exception
 * without printing it, so a 500 left no trace anywhere. A failing signup produced
 * a generic message on screen and a completely silent server log.</p>
 *
 * <p>Both are fixed. {@link ResponseStatusException} now has its own handler that
 * keeps the original status and reason, and every server-side failure is logged
 * with a stack trace and a short error id that is also shown to the user, so a
 * screenshot can be matched to a log line.</p>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * Keeps the status and message chosen by whoever threw the exception.
     *
     * <p>This is the handler that makes downstream problems visible. It is more
     * specific than the catch-all below, so Spring always prefers it.</p>
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleResponseStatus(ResponseStatusException exception) {
        HttpStatusCode status = exception.getStatusCode();
        String message = exception.getReason() != null
                ? exception.getReason()
                : "The request could not be completed.";

        if (status.is5xxServerError()) {
            log.error("Downstream failure -> {} : {}", status, message, exception);
        } else {
            log.warn("Request rejected -> {} : {}", status, message);
        }

        return ResponseEntity.status(status).body(new ApiErrorResponse(message, Instant.now()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("Some of the details you entered are not valid.");
        return build(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableBody(HttpMessageNotReadableException exception) {
        log.warn("Rejected an unreadable request body: {}", exception.getMessage());
        return build(HttpStatus.BAD_REQUEST, "The request body was not valid JSON.");
    }

    @ExceptionHandler({
            ConstraintViolationException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<ApiErrorResponse> handleBadRequest(Exception exception) {
        log.warn("Bad request: {}", exception.getMessage());
        return build(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ApiErrorResponse> handleDuplicateEmail(DuplicateEmailException exception) {
        return build(HttpStatus.CONFLICT, exception.getMessage());
    }

    /**
     * A database constraint stopped the write. Previously this surfaced as an
     * opaque 500; the most common real cause is deleting a saved analysis that
     * still has feedback attached to it.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrity(DataIntegrityViolationException exception) {
        log.warn("Database constraint violation", exception);
        return build(HttpStatus.CONFLICT,
                "That change conflicts with data already saved, so it was not applied.");
    }

    @ExceptionHandler({
            InvalidCredentialsException.class,
            UnauthorizedException.class,
            BadCredentialsException.class,
            AuthenticationException.class
    })
    public ResponseEntity<ApiErrorResponse> handleUnauthorized(Exception exception) {
        return build(HttpStatus.UNAUTHORIZED, exception.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(ResourceNotFoundException exception) {
        return build(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    /**
     * An unknown URL. Not a server fault, so it is not logged as an error.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoResource(NoResourceFoundException exception) {
        return build(HttpStatus.NOT_FOUND, "That endpoint does not exist.");
    }

    /**
     * Genuine last resort: something we did not anticipate went wrong.
     *
     * <p>The full stack trace goes to the server log under a short reference id.
     * The client gets a safe message carrying the same id, so the two can be
     * matched up without ever exposing internals to the browser.</p>
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGeneric(Exception exception) {
        String errorId = UUID.randomUUID().toString().substring(0, 8);
        log.error("Unhandled exception [ref {}]", errorId, exception);
        return build(HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong on the server. Reference: " + errorId);
    }

    private ResponseEntity<ApiErrorResponse> build(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(new ApiErrorResponse(message, Instant.now()));
    }
}
