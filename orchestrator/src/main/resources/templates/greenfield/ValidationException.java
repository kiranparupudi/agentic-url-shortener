package com.agentic.urlshortener;

public class ValidationException extends RuntimeException {
    public ValidationException(String message) {
        super(message);
    }
}
