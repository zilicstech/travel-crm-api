package com.voyra.crm.exception;

import lombok.Getter;

import java.util.Map;

/** A 409 the UI branches on by {@code code} (PRICE_CHANGED, REVIEW_EXPIRED, TEST_WALLET_REQUIRED, LIVE_WALLET_REQUIRED; pricing rules: RULE_IN_USE, DEFAULT_RULE). */
@Getter
public class BookingConflictException extends RuntimeException {

    private final String code;
    private final Map<String, Object> details;

    public BookingConflictException(String code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details;
    }
}
