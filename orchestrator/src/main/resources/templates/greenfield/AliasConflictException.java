package com.agentic.urlshortener;

public class AliasConflictException extends RuntimeException {
    public AliasConflictException(String message) {
        super(message);
    }
}
