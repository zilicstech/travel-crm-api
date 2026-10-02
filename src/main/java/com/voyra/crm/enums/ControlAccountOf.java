package com.voyra.crm.enums;

/**
 * What a control account's balance must reconcile against on the subsidiary-ledger side - see
 * {@code service.ControlAccountReconciliationService} and Rule 1.7.1.
 */
public enum ControlAccountOf {
    ACCOUNTS_RECEIVABLE, ACCOUNTS_PAYABLE, CLIENT_ADVANCES, CLIENT_UNEARNED, SUPPLIER_ADVANCES
}
