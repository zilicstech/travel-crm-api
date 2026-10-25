package com.voyra.crm.enums;

/** FLAGGED: a capture whose amount or currency did not match the order - never credited, alerted for a human. EXPIRED: unpaid after 24 h. */
public enum GatewayOrderStatus {
    CREATED, PAID, FAILED, FLAGGED, EXPIRED
}
