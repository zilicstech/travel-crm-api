package com.voyra.crm.exception;

/** The agency owes the platform money; new holds are refused until a credit settles it: a 409 RECEIVABLE_DUE. */
public class ReceivableDueException extends RuntimeException {

    public ReceivableDueException() {
        super("An amount is due to Voyra - add money to the wallet to settle it before booking");
    }
}
