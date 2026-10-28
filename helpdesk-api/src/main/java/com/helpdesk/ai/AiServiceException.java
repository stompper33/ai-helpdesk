package com.helpdesk.ai;

import org.springframework.http.HttpStatus;

/** ai-service is down, timed out, or returned something we can't use. */
public class AiServiceException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public AiServiceException(String code, HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
