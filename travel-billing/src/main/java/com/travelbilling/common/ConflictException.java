package com.travelbilling.common;

/**
 * The request is well-formed but clashes with a business rule (mixed currency, same id twice, ...).
 * Mapped to HTTP 409. `error` is the machine-readable code, e.g. "mixed_currency".
 */
public class ConflictException extends RuntimeException {

    private final String error;

    public ConflictException(String error, String message) {
        super(message);
        this.error = error;
    }

    public String error() {
        return error;
    }
}
