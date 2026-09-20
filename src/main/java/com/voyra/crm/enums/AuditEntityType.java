package com.voyra.crm.enums;

/**
 * The kind of record an {@code audit_log} row is about. {@code LEAD_SERVICE} refers to the JPA
 * entity {@code entity.LeadService} (one flight/hotel/visa/transfer instance on a lead) - its
 * audit call sites live in {@code service.ServiceInstanceService}, not {@code service.LeadService},
 * which is a different class and audits {@code LEAD} itself.
 *
 * <p>{@code COMMUNICATION}, {@code FEEDBACK} and {@code SHIFT_HANDOVER} are declared ahead of the
 * milestones that add those entities, so this enum does not need to churn later.
 */
public enum AuditEntityType {
    LEAD, LEAD_SERVICE, CLIENT, MEMBER, BOOKING, VISA, CLIENT_INVOICE, SUPPLIER_INVOICE, VENDOR,
    COMMUNICATION, FEEDBACK, SHIFT_HANDOVER, TAX_RATE_CONFIG, INVOICE, PAYMENT_RECEIPT, CREDIT_NOTE,
    SUPPLIER_PAYMENT, SUPPLIER_CREDIT_NOTE
}
