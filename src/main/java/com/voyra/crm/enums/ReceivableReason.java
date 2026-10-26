package com.voyra.crm.enums;

public enum ReceivableReason {
    CANCEL_NET_NEGATIVE("commission taken back on a cancelled booking"),
    CHARGEBACK("a payment reversed by the bank (chargeback)"),
    LATE_TICKET("a ticket issued after its hold was released"),
    TDS_SHORTFALL("TDS on a commission");

    /** Plain words for statements and screens; the code itself is never shown. */
    private final String text;

    ReceivableReason(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }
}
