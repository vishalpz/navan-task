package com.travelbilling.api;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.travelbilling.common.ConflictException;
import com.travelbilling.common.FieldPaths;
import com.travelbilling.common.NotFoundException;
import com.travelbilling.common.PublicIds;
import com.travelbilling.common.RequestValidationException;
import com.travelbilling.transaction.DuplicateExternalIdException;

/** The one place that maps exceptions to HTTP status codes and the ApiError body. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DuplicateExternalIdException.class)
    ResponseEntity<ApiError> duplicate(DuplicateExternalIdException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(
            "duplicate_external_id", "a transaction with this external_id already exists",
            null, PublicIds.transaction(e.existingId())));
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ApiError> conflict(ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(e.error(), e.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ApiError> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of("not_found", e.getMessage()));
    }

    /** Service-level validation (type-specific metadata). */
    @ExceptionHandler(RequestValidationException.class)
    ResponseEntity<ApiError> invalid(RequestValidationException e) {
        return validationFailed(e.errors());
    }

    /** Controller-level @Valid on the request envelope. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e) {
        List<String> errors = e.getBindingResult().getFieldErrors().stream()
            .map(fe -> FieldPaths.fromJavaPath(fe.getField()) + ": " + fe.getDefaultMessage())
            .sorted()
            .toList();
        return validationFailed(errors);
    }

    /** Body isn't valid JSON, or a value has the wrong type (e.g. type: "cruise", total: "abc"). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        String message = "request body is not valid JSON";
        if (e.getCause() instanceof JsonMappingException jme && !jme.getPath().isEmpty()) {
            message = "invalid value for '" + FieldPaths.fromJson(jme) + "'";
        }
        return ResponseEntity.badRequest().body(ApiError.of("malformed_request", message));
    }

    /** A query/path parameter of the wrong type, e.g. ?limit=abc. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> badParameter(MethodArgumentTypeMismatchException e) {
        return validationFailed(List.of(e.getName() + ": invalid value"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> other(Exception e) {
        // Spring's own MVC exceptions (unknown route 404, wrong method 405, ...) carry their status.
        if (e instanceof ErrorResponse springError) {
            return ResponseEntity.status(springError.getStatusCode())
                .body(ApiError.of("request_error", springError.getBody().getDetail()));
        }
        // Anything else is our bug: log it in full, don't leak internals to the client.
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiError.of("internal_error", "unexpected server error"));
    }

    private static ResponseEntity<ApiError> validationFailed(List<String> errors) {
        return ResponseEntity.badRequest().body(new ApiError("validation_failed", "request is invalid", errors, null));
    }
}
