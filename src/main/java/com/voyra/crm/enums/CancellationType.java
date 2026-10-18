package com.voyra.crm.enums;

/** How a booking was cancelled - drives the charges the supplier keeps (a void costs a few hundred, a no-show refunds taxes only). */
public enum CancellationType {
    VOID("Void"), CANCELLATION("Cancellation"), NO_SHOW("No show"), FULL_REFUND("Full refund");

    private final String label;

    CancellationType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
