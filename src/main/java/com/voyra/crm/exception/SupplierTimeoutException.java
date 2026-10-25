package com.voyra.crm.exception;

/** The supplier did not answer in time. For a booking call the outcome is unknown, so callers must poll, never retry. */
public class SupplierTimeoutException extends SupplierException {

    public SupplierTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
