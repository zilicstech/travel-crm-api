package com.voyra.crm.enums;

/** serviceType is set on the entity only when kind is SERVICE_PREFERENCE (or, historically, SUPPLIER). */
public enum AgencySettingKind {
    LEAD_SOURCE, TRAVEL_CATEGORY, DOCUMENT_TYPE, SERVICE_PREFERENCE,

    /**
     * @deprecated Suppliers are now real records at {@code entity.Vendor} / {@code /api/vendors}.
     * This constant survives for one release only, as a read-only compatibility shim -
     * {@code AgencySettingService} proxies {@code list(SUPPLIER, ...)} to
     * {@code VendorService.listAsLegacySettings} and rejects every write with this kind. Remove
     * once every caller of {@code GET /api/agency/settings?kind=SUPPLIER} is repointed at
     * {@code /api/vendors}.
     */
    @Deprecated
    SUPPLIER
}
