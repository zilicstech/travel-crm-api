package com.voyra.crm.models;

import com.voyra.crm.enums.SupplyNature;

import java.math.BigDecimal;

/**
 * Input to {@link com.voyra.crm.service.TaxEngine#compute}. Internal computation model -
 * never serialized, never persisted (blueprint §1, models/). The HTTP-facing shape is
 * {@code dto.TaxPreviewRequest}; controllers map into this, never the other way round.
 */
public record TaxComputationRequest(
        String clientId,
        BigDecimal taxableAmount,
        SupplyNature supplyNature,
        String placeOfSupplyCodeOverride,
        boolean exportOfServiceRequested,
        String currencyCode
) {
}
