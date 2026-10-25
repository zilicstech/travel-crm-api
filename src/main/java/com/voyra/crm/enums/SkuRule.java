package com.voyra.crm.enums;

/** What a controller needs before an agency may call it (spec §8): nothing, or exactly one SKU. */
public enum SkuRule {
    NEVER_GATED(null),
    CRM_CORE(Sku.CRM_CORE),
    STAFF_SEATS(Sku.STAFF_SEATS),
    ACCOUNTS_GST(Sku.ACCOUNTS_GST),
    REPORTS(Sku.REPORTS),
    VISA(Sku.VISA);

    private final Sku sku;

    SkuRule(Sku sku) {
        this.sku = sku;
    }

    /** null for NEVER_GATED. */
    public Sku sku() {
        return sku;
    }
}
