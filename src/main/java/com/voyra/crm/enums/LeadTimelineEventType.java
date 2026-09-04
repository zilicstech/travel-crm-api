package com.voyra.crm.enums;

/**
 * Kinds of event recorded on the lead activity stream.
 *
 * <p>Timeline rows are written by the service on state changes and are append-only; there is
 * no client-facing write endpoint, so this enum is never bound from a request body. Adding a
 * value here means adding the service call site that emits it.
 */
public enum LeadTimelineEventType {
    LEAD_CREATED,
    STATUS_CHANGED,
    DETAILS_UPDATED,
    ASSIGNED,
    FOLLOW_UP_SET,
    MEMBER_ADDED,
    MEMBER_UPDATED,
    MEMBER_DROPPED,
    PROPOSAL_ITEM_ADDED,
    PROPOSAL_ITEM_REMOVED,
    PROPOSAL_OPTION_SELECTED,
    PROPOSAL_LOCKED,
    PROPOSAL_UNLOCKED,
    PROPOSAL_LINK_GENERATED,
    PROPOSAL_APPROVED,
    NOTE_ADDED,
    DOCUMENT_UPLOADED,
    SERVICE_ADDED,
    SERVICE_UPDATED,
    SERVICE_REMOVED,
    VOUCHER_ADDED,
    VOUCHER_REMOVED,
    INVOICE_ADDED
}
