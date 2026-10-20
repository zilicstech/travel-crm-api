package com.voyra.crm.util;

import com.voyra.crm.enums.MarkupMode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A voucher's fare charges as the supplier's ticket shows them (Basic, YQ, YR, K3, OC, other tax, processing,
 * other charges) and the commission the supplier pays the agency on it. Charges are in the voucher's purchase
 * currency and add up to its purchase price; the commission is in that currency too, like the supplier bill.
 */
public final class VoucherCharges {

    /** In the order the client's ticket lists them. */
    public static final List<String> KEYS = List.of("basic", "yq", "yr", "k3", "oc", "otherTax", "processing", "otherCharges");

    /** As the ticket prints them. */
    public static final Map<String, String> LABELS = Map.of("basic", "Basic", "yq", "YQ Tax", "yr", "YR Tax", "k3", "K3 Tax",
            "oc", "OC Tax", "otherTax", "Other Tax", "processing", "Processing Charges", "otherCharges", "Other Charges");

    /**
     * "Basic 8,200.00 · YQ Tax 1,400.00 · Markup 1,000.00" for a line billing {@code amount}; null when the charges don't
     * fit inside it (a line that bills only part of a voucher).
     */
    public static String describe(Map<String, BigDecimal> charges, BigDecimal amount, java.util.function.Function<BigDecimal, String> money) {
        if (charges == null || charges.isEmpty() || amount == null) {
            return null;
        }
        BigDecimal markup = amount.subtract(total(charges));
        if (markup.signum() < 0) {
            return null;
        }
        List<String> parts = new java.util.ArrayList<>();
        KEYS.stream().filter(charges::containsKey).forEach(k -> parts.add(LABELS.get(k) + " " + money.apply(charges.get(k))));
        if (markup.signum() > 0) parts.add("Markup " + money.apply(markup));
        return String.join(" · ", parts);
    }

    /** TDS a supplier deducts on commission when none is typed (sec. 194H). */
    public static final BigDecimal DEFAULT_TDS_RATE = new BigDecimal("2");

    private VoucherCharges() {
    }

    /** Known keys only, each 0 or more at 2dp, zeros dropped, in KEYS order; null when nothing is left. */
    public static Map<String, BigDecimal> clean(Map<String, BigDecimal> in) {
        if (in == null || in.isEmpty()) {
            return null;
        }
        for (String key : in.keySet()) {
            if (!KEYS.contains(key)) {
                throw new IllegalArgumentException("Unknown charge " + key);
            }
        }
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (String key : KEYS) {
            BigDecimal v = in.get(key);
            if (v == null || v.signum() == 0) continue;
            if (v.signum() < 0) {
                throw new IllegalArgumentException("A charge can't be negative");
            }
            out.put(key, v.setScale(2, RoundingMode.HALF_UP));
        }
        return out.isEmpty() ? null : out;
    }

    /** The same charges, whatever the scale they were stored at. */
    public static boolean same(Map<String, BigDecimal> a, Map<String, BigDecimal> b) {
        Map<String, BigDecimal> x = a == null ? Map.of() : a, y = b == null ? Map.of() : b;
        return x.keySet().equals(y.keySet()) && x.keySet().stream().allMatch(k -> x.get(k).compareTo(y.get(k)) == 0);
    }

    public static BigDecimal total(Map<String, BigDecimal> charges) {
        return charges == null ? BigDecimal.ZERO : charges.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * The purchase price a request is priced at once it carries charges: their total. A purchase price, or a
     * base net cost, sent beside them must agree.
     */
    public static BigDecimal purchasePrice(Map<String, BigDecimal> charges, BigDecimal purchasePrice, BigDecimal netCost, boolean inBase) {
        BigDecimal total = total(charges);
        if (total.signum() <= 0) {
            throw new IllegalArgumentException("Enter the fare charges");
        }
        BigDecimal sent = purchasePrice != null ? purchasePrice : inBase ? netCost : null;
        if (sent != null && sent.compareTo(total) != 0) {
            throw new IllegalArgumentException("The purchase price (" + sent + ") isn't the total of the charges (" + total + ")");
        }
        return total;
    }

    /** The charges in base at {@code rate}, adding up to {@code baseTotal} exactly - any rounding goes on the largest. */
    public static Map<String, BigDecimal> toBase(Map<String, BigDecimal> charges, BigDecimal rate, BigDecimal baseTotal) {
        if (charges == null) {
            return null;
        }
        BigDecimal r = rate != null ? rate : BigDecimal.ONE;
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        String largest = null;
        for (Map.Entry<String, BigDecimal> e : charges.entrySet()) {
            out.put(e.getKey(), e.getValue().multiply(r).setScale(2, RoundingMode.HALF_UP));
            if (largest == null || e.getValue().compareTo(charges.get(largest)) > 0) largest = e.getKey();
        }
        if (largest != null && baseTotal != null) {
            out.merge(largest, baseTotal.subtract(total(out)), BigDecimal::add);
        }
        return out;
    }

    /** A voucher's commission and the supplier's TDS on it, in the purchase currency. */
    public record Commission(MarkupMode mode, BigDecimal value, BigDecimal tdsRate, BigDecimal amount, BigDecimal tds) {
        public static final Commission NONE = new Commission(null, null, null, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /**
     * PERCENT is of the Basic fare (so needs the charges), FLAT is an amount in the purchase currency. It is at
     * most the gross the supplier bills; TDS is {@code tdsRate} % of it (2 % when not given).
     */
    public static Commission commission(Map<String, BigDecimal> charges, BigDecimal gross, MarkupMode mode,
                                        BigDecimal value, BigDecimal tdsRate) {
        if (mode == null || value == null || value.signum() == 0) {
            return Commission.NONE;
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException("Commission can't be negative");
        }
        BigDecimal amount;
        if (mode == MarkupMode.PERCENT) {
            BigDecimal basic = charges != null ? charges.get("basic") : null;
            if (basic == null || basic.signum() == 0) {
                throw new IllegalArgumentException("Commission as a % is of the Basic fare - enter the fare breakdown");
            }
            amount = basic.multiply(value).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        } else {
            amount = value.setScale(2, RoundingMode.HALF_UP);
        }
        if (gross == null || amount.compareTo(gross) > 0) {
            throw new IllegalArgumentException("The commission is more than the supplier's bill");
        }
        BigDecimal rate = tdsRate != null ? tdsRate : DEFAULT_TDS_RATE;
        if (rate.signum() < 0 || rate.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("TDS on commission is a % between 0 and 100");
        }
        BigDecimal tds = amount.multiply(rate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return new Commission(mode, value, rate, amount, tds);
    }
}
