package com.jbp.exception;

/**
 * Thrown when a request conflicts with the current state of a resource
 * (e.g. creating something that already exists). Maps to HTTP 409.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
