package com.voyra.crm.enums;

/**
 * Inclusion state of one traveller on one lead.
 *
 * <p>A traveller is never removed from a lead by deleting the row - dropping out after
 * documents were collected or a visa was filed is normal in group travel, and that history
 * has to survive. Removal sets DROPPED and records a reason instead.
 */
public enum LeadMemberStatus {
    TENTATIVE, CONFIRMED, DROPPED
}
