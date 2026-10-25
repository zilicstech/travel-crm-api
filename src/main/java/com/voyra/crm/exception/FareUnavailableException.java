package com.voyra.crm.exception;

/** The fare being reviewed is no longer sold (expired or sold out). */
public class FareUnavailableException extends RuntimeException {

    public FareUnavailableException(String message) {
        super(message);
    }
}
