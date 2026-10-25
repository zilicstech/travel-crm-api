package com.voyra.crm.enums;

/** PENDING waits for its next attempt; SENDING is claimed by one job run (lease in next_attempt_at); SENT and FAILED are final. */
public enum NotificationOutboxStatus {
    PENDING, SENDING, SENT, FAILED
}
