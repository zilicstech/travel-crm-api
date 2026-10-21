package com.voyra.crm.exception;

/** Something the caller asked for by id or token doesn't exist (or no longer does): a 404 with this message. */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
