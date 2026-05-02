package com.example.peg.shared;

import org.springframework.http.HttpStatus;

/** Base for API-level errors mapped to HTTP responses by the global handler. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code()       { return code; }
}
