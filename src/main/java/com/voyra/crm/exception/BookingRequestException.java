package com.voyra.crm.exception;

import lombok.Getter;

/** A 400 the booking UI branches on by {@code code} (ISSUING_AGENT_REQUIRED). */
@Getter
public class BookingRequestException extends IllegalArgumentException {

    private final String code;

    public BookingRequestException(String code, String message) {
        super(message);
        this.code = code;
    }
}
