package com.voyra.crm.enums;

/** One email template per kind (NotificationTemplates). */
public enum NotificationKind {
    /** Retired with the low-balance alert (2026-11-19); kept so old outbox rows still read. */
    LOW_BALANCE,
    PLATFORM_ALERT,
    SUBSCRIPTION_EXPIRING,
    SUBSCRIPTION_INVOICE,
    TOPUP_APPROVED,
    TOPUP_REJECTED,
    APPLICATION_MORE_INFO,
    APPLICATION_REJECTED,
    APPLICATION_APPROVED,
    BOOKING_TICKETED,
    BOOKING_FAILED,
    BOOKING_CANCELLED,
    CANCEL_REQUEST_RAISED,
    CANCEL_REQUEST_COMPLETED,
    CANCEL_REQUEST_REJECTED
}
