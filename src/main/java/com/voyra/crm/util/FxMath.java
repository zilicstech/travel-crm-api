package com.voyra.crm.util;

import com.voyra.crm.enums.MarkupMode;
import com.voyra.crm.models.ForeignPriced;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Locale;

/**
 * Turns an amount typed in any currency into the agency's base currency at the rate the person
 * entered (1 unit = rate base). Rounded HALF_UP to 2dp - the same rounding a supplier bill uses
 * for its base totals, so a voucher's cost and its auto-drafted bill always agree.
 */
public final class FxMath {

    private FxMath() {
    }

    /** What was typed, and what it is worth in base. */
    public record Converted(String currency, BigDecimal amount, BigDecimal rate, BigDecimal base) {
        public boolean foreign(String baseCode) {
            return !baseCode.equals(currency);
        }
    }

    /** A priced line in base, plus the foreign figures it came from (null when typed in base). */
    public record Pricing(BigDecimal netCost, BigDecimal markupValue, Converted purchase, Converted markup) {
    }

    public static Converted toBase(String currency, BigDecimal amount, BigDecimal rate, String base) {
        String code = code(currency, base);
        BigDecimal a = amount != null ? amount : BigDecimal.ZERO;
        if (code.equals(base)) {
            return new Converted(code, a, BigDecimal.ONE, a.setScale(2, RoundingMode.HALF_UP));
        }
        if (rate == null || rate.signum() <= 0) {
            throw new IllegalArgumentException("Enter the " + code + " rate for this date (1 " + code + " in " + base + ")");
        }
        return new Converted(code, a, rate, a.multiply(rate).setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * The base cost and base markup of a priced request. A request that gives only {@code netCost}
     * / {@code markupValue} (older callers) is already in base; a {@code purchasePrice} or a flat
     * {@code markupInput} is converted from its own currency.
     */
    public static Pricing resolve(ForeignPriced in, String base) {
        Converted purchase = in.getPurchasePrice() == null ? null
                : toBase(in.getPurchaseCurrency(), in.getPurchasePrice(), in.getPurchaseRate(), base);
        Converted markup = in.getMarkupMode() != MarkupMode.FLAT || in.getMarkupInput() == null ? null
                : toBase(in.getMarkupCurrency(), in.getMarkupInput(), in.getMarkupRate(), base);
        return new Pricing(purchase != null ? purchase.base() : in.getNetCost(),
                markup != null ? markup.base() : in.getMarkupValue(), purchase, markup);
    }

    /** An ISO 4217 code, upper-cased; blank means the base currency. */
    public static String code(String currency, String base) {
        if (currency == null || currency.isBlank()) {
            return base;
        }
        String code = currency.trim().toUpperCase(Locale.ROOT);
        try {
            Currency.getInstance(code);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown currency code " + code);
        }
        return code;
    }
}
