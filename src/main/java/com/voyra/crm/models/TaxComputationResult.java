package com.voyra.crm.models;

import com.voyra.crm.enums.TaxTreatment;

import java.math.BigDecimal;

/**
 * Output of {@link com.voyra.crm.service.TaxEngine#compute}. Internal computation model -
 * never serialized, never persisted. Every rate here is exactly what an invoice line would
 * freeze at issue, so callers (the preview endpoint today, the invoice line builder from
 * Epic 3 on) can persist these fields verbatim without re-deriving anything.
 */
public record TaxComputationResult(
        TaxTreatment taxTreatment,
        String placeOfSupplyCode,
        /** The resolved GST slab's own SAC code - the line builder's fallback when the caller
         *  doesn't specify one. Null for EXPORT_OF_SERVICE/EXEMPT and for a TCS-only result. */
        String sacCode,
        BigDecimal taxableValue,
        BigDecimal gstRatePercent,
        BigDecimal cgstRatePercent,
        BigDecimal sgstRatePercent,
        BigDecimal igstRatePercent,
        BigDecimal cgstAmount,
        BigDecimal sgstAmount,
        BigDecimal igstAmount,
        BigDecimal gstTotal,
        BigDecimal tcsRatePercent,
        String tcsSection,
        BigDecimal tcsBaseAmount,
        BigDecimal tcsAmount,
        BigDecimal grandTotal
) {
}
