package com.voyra.crm.exception;

import lombok.Getter;

import java.math.BigDecimal;

/** The wallet's available balance does not cover the request: a 409 INSUFFICIENT_BALANCE carrying what is available. */
@Getter
public class InsufficientBalanceException extends RuntimeException {

    private final BigDecimal available;

    public InsufficientBalanceException(BigDecimal available) {
        super("Insufficient wallet balance - available " + available);
        this.available = available;
    }
}
