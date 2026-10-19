package com.voyra.crm.exception;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses Spring/PostgreSQL messages for unique-violation details so a 409 says in plain words what is duplicated. */
final class DuplicateConstraintMessageParser {

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

    /** What people are told per unique index; anything else gets {@link #GENERIC} - never the index or column name. */
    private static final Map<String, String> MESSAGES = Map.ofEntries(
            Map.entry("idx_tenant_owner_email", "This email is already used by another login"),
            Map.entry("idx_agent_email", "This email is already used by another login"),
            Map.entry("idx_platform_admin_email", "This email is already used by another login"),
            Map.entry("idx_client_identifier", "A client with this phone or email already exists"),
            Map.entry("uq_sup_inv_number", "This vendor already has a bill with this invoice number"),
            Map.entry("uq_ledger_account_code", "An account with this code already exists"),
            Map.entry("idx_feedback_booking", "Feedback for this trip has already been submitted."),
            Map.entry("idx_invoice_booking_draft", "This booking already has a draft invoice"),
            Map.entry("uq_bank_transaction_dup", "This bank transaction has already been imported"),
            Map.entry("idx_supplier_credential_provider", "Credentials for this supplier are already saved"),
            Map.entry("uq_exchange_rate_date_pair", "A rate for this currency and date already exists"),
            Map.entry("idx_lead_members_pair", "This traveller is already on the lead"));
    static final String GENERIC = "This record already exists - refresh and try again";

    static String humanMessage(String message) {
        Matcher constraintMatcher = UNIQUE_CONSTRAINT_NAME.matcher(message == null ? "" : message);
        return constraintMatcher.find() ? MESSAGES.getOrDefault(constraintMatcher.group(1), GENERIC) : GENERIC;
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
