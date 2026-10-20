package com.voyra.crm.models;

import com.voyra.crm.enums.MarkupMode;

import java.math.BigDecimal;

/**
 * A request that prices something: a cost and markup in base, or a purchase price and a flat
 * markup typed in their own currencies with a rate to base. See {@link com.voyra.crm.util.FxMath#resolve}.
 */
public interface ForeignPriced {
    BigDecimal getNetCost();

    MarkupMode getMarkupMode();

    BigDecimal getMarkupValue();

    String getPurchaseCurrency();

    BigDecimal getPurchasePrice();

    BigDecimal getPurchaseRate();

    String getMarkupCurrency();

    BigDecimal getMarkupInput();

    BigDecimal getMarkupRate();
}
