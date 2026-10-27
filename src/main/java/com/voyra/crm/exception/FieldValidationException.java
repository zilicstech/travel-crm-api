package com.voyra.crm.exception;

import lombok.Getter;

/** A rule only the database can check (e.g. a reused reference) failed for one request field: a 400 shaped like bean validation. */
@Getter
public class FieldValidationException extends RuntimeException {
    private final String field;

    public FieldValidationException(String field, String message) {
        super(message);
        this.field = field;
    }
}
