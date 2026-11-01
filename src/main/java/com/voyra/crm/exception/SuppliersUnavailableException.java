package com.voyra.crm.exception;

/** Every supplier asked for a search failed or timed out: a 503 with code SUPPLIERS_UNAVAILABLE. */
public class SuppliersUnavailableException extends RuntimeException {

    public SuppliersUnavailableException() {
        super("Suppliers are not responding; try again in a minute");
    }
}
