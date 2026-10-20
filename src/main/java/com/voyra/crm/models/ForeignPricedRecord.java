package com.voyra.crm.models;

import com.voyra.crm.util.FxMath;

import java.math.BigDecimal;

/** A stored voucher or quote line that keeps the foreign figures it was priced from (null when typed in base). */
public interface ForeignPricedRecord {
    String getPurchaseCurrency();

    BigDecimal getPurchasePrice();

    BigDecimal getPurchaseRate();

    void setPurchaseCurrency(String v);

    void setPurchasePrice(BigDecimal v);

    void setPurchaseRate(BigDecimal v);

    void setMarkupCurrency(String v);

    void setMarkupInput(BigDecimal v);

    void setMarkupRate(BigDecimal v);

    /** Keeps only figures typed in another currency - a base amount is already netCost / markupValue. */
    default void keepForeign(FxMath.Pricing p, String base) {
        FxMath.Converted purchase = p.purchase() != null && p.purchase().foreign(base) ? p.purchase() : null;
        setPurchaseCurrency(purchase != null ? purchase.currency() : null);
        setPurchasePrice(purchase != null ? purchase.amount() : null);
        setPurchaseRate(purchase != null ? purchase.rate() : null);
        FxMath.Converted markup = p.markup() != null && p.markup().foreign(base) ? p.markup() : null;
        setMarkupCurrency(markup != null ? markup.currency() : null);
        setMarkupInput(markup != null ? markup.amount() : null);
        setMarkupRate(markup != null ? markup.rate() : null);
    }
}
