package com.travelbilling.common;

import java.util.List;

/**
 * Thrown by the service when input is invalid in a way the controller couldn't check up front
 * (e.g. type-specific metadata). Mapped to HTTP 400.
 *
 * It's a RuntimeException on purpose: Spring's @Transactional rolls back on it automatically.
 */
public class RequestValidationException extends RuntimeException {

    private final List<String> errors;

    public RequestValidationException(List<String> errors) {
        super(String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
