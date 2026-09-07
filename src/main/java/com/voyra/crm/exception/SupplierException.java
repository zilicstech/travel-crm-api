package com.voyra.crm.exception;

/**
 * Thrown by a supplier search provider (Tripjack or otherwise) when the outbound HTTP call
 * fails or times out. Deliberately its own type rather than letting the failure surface as
 * whatever the HTTP client throws - {@code GlobalExceptionHandler.handleGeneric} echoes
 * {@code ex.getMessage()} to the client, and an HTTP client's exception message embeds the
 * request URI (and, for a query-string-keyed vendor, the API key). This type carries the
 * real cause for server-side logging only; the client always gets a fixed, safe message.
 */
public class SupplierException extends RuntimeException {

    public SupplierException(String message, Throwable cause) {
        super(message, cause);
    }
}
