package com.voyra.crm.exception;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses Spring/PostgreSQL messages for unique-violation details so a 409 can name the offending field. */
final class DuplicateConstraintMessageParser {

    private static final Pattern KEY_DETAIL =
            Pattern.compile("Key \\(([^)]+)\\)\\s*=", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern UNIQUE_CONSTRAINT_NAME =
            Pattern.compile("unique constraint \"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final int MAX_CHAIN_DEPTH = 5;

    private DuplicateConstraintMessageParser() {
    }

    static boolean isDuplicateUniqueViolation(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("duplicate key value violates unique constraint");
    }

    static String extractFieldLabel(String message) {
        if (message == null) {
            return "value";
        }
        Matcher keyMatcher = KEY_DETAIL.matcher(message);
        if (keyMatcher.find()) {
            return keyMatcher.group(1);
        }
        Matcher constraintMatcher = UNIQUE_CONSTRAINT_NAME.matcher(message);
        if (constraintMatcher.find()) {
            return constraintMatcher.group(1);
        }
        return "value";
    }

    /** PostgreSQL's DETAIL is usually on the root cause, so concatenate the chain (depth-capped). */
    static String collectMessageChain(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        Throwable current = ex;
        int depth = 0;
        while (current != null && depth < MAX_CHAIN_DEPTH) {
            if (current.getMessage() != null) {
                if (sb.length() > 0) {
                    sb.append(" | ");
                }
                sb.append(current.getMessage());
            }
            current = current.getCause();
            depth++;
        }
        return sb.toString();
    }
}
