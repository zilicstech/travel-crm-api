package com.voyra.crm.enums;

/** How a {@code payment_receipt} amount moved. Typed, not a free-text instrument field. */
public enum PaymentMode {
    CASH, BANK_TRANSFER, UPI, CHEQUE, CARD, WALLET, ADJUSTMENT, OTHER
}
