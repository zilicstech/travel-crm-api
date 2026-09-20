package com.voyra.crm.enums;

/**
 * The lifecycle of one lead_service instance. BOOKED is derived, not chosen: it is set
 * automatically the moment the first live Booking is logged against the service (see
 * ServiceBookingStatusSync), and reverts to CONFIRMED when none remain - never set by hand.
 */
public enum ServiceStatus {
    NOT_STARTED, IN_PROGRESS, AWAITING_CLIENT, CONFIRMED, BOOKED, CANCELLED
}
