package com.voyra.crm.enums;

/**
 * Where a booking's cancellation stands, worked out from the booking, its Voyra request and its follow-ups
 * ({@link com.voyra.crm.util.CancellationStatusRules}); never set by hand.
 */
public enum CancellationStatus {
    /** A cancellation is scheduled for a later date. */
    CANCELLATION_PENDING,
    /** A scheduled cancellation is due today or overdue. */
    FOLLOW_UP_REQUIRED,
    /** Sent to Voyra, not picked up yet. */
    REQUESTED,
    /** Voyra is cancelling it with the supplier. */
    IN_PROGRESS,
    /** Voyra could not cancel it. */
    FAILED,
    /** The agency took its request back. */
    WITHDRAWN,
    /** Voyra cancelled some passengers or journeys; the booking stays live. */
    PARTLY_CANCELLED,
    /** Cancelled, and a refund to the client or from the supplier has not moved yet. */
    REFUND_PENDING,
    /** Cancelled, and every refund has moved. */
    REFUND_PROCESSED,
    /** Cancelled with nothing to refund. */
    CANCELLED,
    /** A scheduled cancellation was closed and the booking kept. */
    NOT_CANCELLED
}
