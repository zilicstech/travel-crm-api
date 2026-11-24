package com.voyra.crm.util;

import com.voyra.crm.entity.VisaField;
import com.voyra.crm.enums.VisaFieldType;
import com.voyra.crm.exception.FieldValidationException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Checks the values of configured visa fields by type; every problem comes back at once, keyed {@code values.<field id>}. */
public final class VisaFieldValues {

    public static final int MAX_TEXT = 500;

    private VisaFieldValues() {
    }

    /**
     * @param values    field id -> value as sent ("" or blank clears it)
     * @param asked     the active fields of the application's country, by id; any other id is refused
     * @param filesGiven ids of the File fields a file is sent for
     * @param mandatory the fields that must end up filled (on create every mandatory field asked; on edit the mandatory ones sent)
     * @return the values cleaned: trimmed, a Dropdown in its option's spelling, Yes/No as "true"/"false", a cleared one as ""
     */
    public static Map<String, String> check(Map<String, String> values, Map<String, VisaField> asked,
                                            Set<String> filesGiven, Collection<VisaField> mandatory) {
        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, String> clean = new LinkedHashMap<>();
        values.forEach((id, raw) -> {
            VisaField field = asked.get(id);
            String value = raw == null ? "" : raw.trim();
            String problem = field == null ? "This field is not asked for this country"
                    : value.isEmpty() && field.getType() != VisaFieldType.FILE ? null
                    : problem(field, value);
            if (problem != null) {
                errors.put(key(id), problem);
            } else {
                clean.put(id, value.isEmpty() ? "" : canonical(field, value));
            }
        });
        for (String id : filesGiven) {
            VisaField field = asked.get(id);
            if (field == null || field.getType() != VisaFieldType.FILE) {
                errors.put(key(id), "This field takes no file");
            }
        }
        for (VisaField field : mandatory) {
            boolean filled = field.getType() == VisaFieldType.FILE
                    ? filesGiven.contains(field.getId())
                    : !clean.getOrDefault(field.getId(), "").isEmpty();
            if (!filled && !errors.containsKey(key(field.getId()))) {
                errors.put(key(field.getId()), field.getType() == VisaFieldType.FILE ? "Upload this file" : "This field is required");
            }
        }
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
        return clean;
    }

    public static String key(String fieldId) {
        return "values." + fieldId;
    }

    private static String problem(VisaField field, String value) {
        return switch (field.getType()) {
            case TEXT -> value.length() > MAX_TEXT ? "Write at most " + MAX_TEXT + " characters" : null;
            case DROPDOWN -> option(field, value) == null ? "Pick one of: " + String.join(", ", field.getOptions()) : null;
            case DATE -> date(value) ? null : "Enter a date as YYYY-MM-DD";
            case YES_NO -> "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value) ? null : "Pick Yes or No";
            case FILE -> "Upload the file instead";
        };
    }

    private static String canonical(VisaField field, String value) {
        return switch (field.getType()) {
            case DROPDOWN -> option(field, value);
            case YES_NO -> value.toLowerCase(java.util.Locale.ROOT);
            default -> value;
        };
    }

    private static String option(VisaField field, String value) {
        return field.getOptions().stream().filter(o -> o.equalsIgnoreCase(value)).findFirst().orElse(null);
    }

    private static boolean date(String value) {
        try {
            LocalDate.parse(value);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
