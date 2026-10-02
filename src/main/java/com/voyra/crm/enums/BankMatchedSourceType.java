package com.voyra.crm.enums;

/** What kind of document a bank_transaction's matched_source_id points at. BANK_MATCH_RULE means matched_source_id is a bank_match_rule id, not a posted document. */
public enum BankMatchedSourceType {
    INVOICE, SUPPLIER_INVOICE, BANK_MATCH_RULE
}
