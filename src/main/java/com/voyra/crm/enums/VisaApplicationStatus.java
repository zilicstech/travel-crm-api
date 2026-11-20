package com.voyra.crm.enums;

/**
 * Where a lead's Visa service stands with the embassy, set by hand on the service (owner ruling 2026-11-20). Separate
 * from the lead status, which stays the sales funnel, and from the Visa module's derived {@link VisaStatus}.
 */
public enum VisaApplicationStatus {
    NOT_APPLIED, APPLIED, PENDING, CONFIRMED, REJECTED
}
