package com.voyra.crm.enums;

/** How a statement's own columns express an amount - one signed column, or separate debit/credit columns. */
public enum BankAmountConvention {
    SINGLE_SIGNED, DEBIT_CREDIT_COLUMNS
}
