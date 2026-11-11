package com.travelbilling.common;

/** Thrown when an id doesn't exist. Mapped to HTTP 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
