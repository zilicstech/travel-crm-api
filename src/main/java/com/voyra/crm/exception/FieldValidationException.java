package com.voyra.crm.exception;

import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.Map;

/** A rule only the database can check (e.g. a reused reference) failed for one or more request fields: a 400 shaped like bean validation. */
@Getter
public class FieldValidationException extends RuntimeException {
    private final String field;
    private final Map<String, String> errors;

    public FieldValidationException(String field, String message) {
        super(message);
        this.field = field;
        this.errors = Map.of(field, message);
    }

    /** Several fields at once (field -> message, in order); the first one is {@link #getField()} and the message. */
    public FieldValidationException(Map<String, String> errors) {
        super(errors.values().iterator().next());
        this.field = errors.keySet().iterator().next();
        this.errors = new LinkedHashMap<>(errors);
    }
}
