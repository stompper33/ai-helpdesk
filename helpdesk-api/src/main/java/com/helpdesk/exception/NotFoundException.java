package com.helpdesk.exception;

/** Mapped to HTTP 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String entity, Object id) {
        super(entity + " not found: " + id);
    }
}
