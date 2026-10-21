package com.voyra.crm.util;

import com.voyra.crm.entity.InvoiceLineItem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How much of an invoice is each booking's, when one invoice bills several bookings of a client:
 * a booking's weight is its lines' taxable value over the invoice's. A line with no booking counts
 * for the invoice's first booking, and so does everything on a zero-value invoice. An invoice of one
 * booking is one share of weight 1, so its figures are exactly the invoice's.
 */
public final class InvoiceShares {

    private InvoiceShares() {
    }

    public record Share(String bookingId, BigDecimal taxable, BigDecimal weight) {
    }

    /** Shares in line order, the invoice's first booking first. */
    public static Map<String, Share> of(String primaryBookingId, List<InvoiceLineItem> lines) {
        Map<String, BigDecimal> taxable = new LinkedHashMap<>();
        String primary = primaryBookingId != null ? primaryBookingId
                : lines.stream().map(InvoiceLineItem::getBookingId).filter(java.util.Objects::nonNull).findFirst().orElse(null);
        if (primary != null) {
            taxable.put(primary, BigDecimal.ZERO);
        }
        for (InvoiceLineItem line : lines) {
            String booking = line.getBookingId() != null ? line.getBookingId() : primary;
            if (booking == null) continue;
            BigDecimal value = line.getTaxableValue() != null ? line.getTaxableValue()
                    : line.getLineSubtotal() != null ? line.getLineSubtotal() : BigDecimal.ZERO;
            taxable.merge(booking, value, BigDecimal::add);
        }
        BigDecimal total = taxable.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Share> shares = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> e : taxable.entrySet()) {
            BigDecimal weight = total.signum() == 0
                    ? (e.getKey().equals(primary) ? BigDecimal.ONE : BigDecimal.ZERO)
                    : e.getValue().divide(total, 10, RoundingMode.HALF_UP);
            shares.put(e.getKey(), new Share(e.getKey(), e.getValue(), weight));
        }
        return shares;
    }

    /**
     * Shares re-weighted by what each booking is billed: its own lines plus the tax on its own fee. Under GST on the
     * markup a booking's tax follows its markup, not its price, so weighting the grand total by price would move tax
     * between bookings. {@code passThrough} is each booking's supplier cost on the invoice; with no fee to go on the
     * price weights stand.
     */
    public static Map<String, Share> byBilled(Map<String, Share> shares, BigDecimal grand, Map<String, BigDecimal> passThrough) {
        BigDecimal taxable = shares.values().stream().map(Share::taxable).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, BigDecimal> fee = new LinkedHashMap<>();
        shares.values().forEach(s -> fee.put(s.bookingId(), s.taxable().subtract(passThrough.getOrDefault(s.bookingId(), BigDecimal.ZERO)).max(BigDecimal.ZERO)));
        BigDecimal feeTotal = fee.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (grand == null || grand.signum() <= 0 || feeTotal.signum() <= 0) {
            return shares;
        }
        BigDecimal tax = grand.subtract(taxable);
        Map<String, Share> out = new LinkedHashMap<>();
        for (Share s : shares.values()) {
            BigDecimal billed = s.taxable().add(tax.multiply(fee.get(s.bookingId())).divide(feeTotal, 10, RoundingMode.HALF_UP));
            out.put(s.bookingId(), new Share(s.bookingId(), s.taxable(), billed.divide(grand, 10, RoundingMode.HALF_UP)));
        }
        return out;
    }

    /** {@code amount} split by weight to 2dp; the rounding remainder goes to the largest share, so the parts always add up to it. */
    public static Map<String, BigDecimal> split(BigDecimal amount, Map<String, Share> shares) {
        Map<String, BigDecimal> parts = new LinkedHashMap<>();
        if (shares.isEmpty() || amount == null) {
            return parts;
        }
        BigDecimal sum = BigDecimal.ZERO;
        String largest = null;
        for (Share s : shares.values()) {
            BigDecimal part = amount.multiply(s.weight()).setScale(2, RoundingMode.HALF_UP);
            parts.put(s.bookingId(), part);
            sum = sum.add(part);
            if (largest == null || s.weight().compareTo(shares.get(largest).weight()) > 0) {
                largest = s.bookingId();
            }
        }
        parts.merge(largest, amount.setScale(2, RoundingMode.HALF_UP).subtract(sum), BigDecimal::add);
        return parts;
    }

    /** This booking's part of {@code amount} (zero when it has no share). */
    public static BigDecimal part(BigDecimal amount, Map<String, Share> shares, String bookingId) {
        return split(amount, shares).getOrDefault(bookingId, BigDecimal.ZERO);
    }

    /**
     * This booking's part of amounts that each name the booking they are for (receipts, credit notes):
     * everything named for it, plus its weighted part of anything named for no booking on the invoice.
     * On an invoice of one booking that is all of them.
     */
    public static BigDecimal attributed(String bookingId, Map<String, Share> shares, List<Map.Entry<String, BigDecimal>> amounts) {
        BigDecimal own = BigDecimal.ZERO;
        BigDecimal unnamed = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> a : amounts) {
            if (a.getValue() == null) continue;
            if (shares.size() <= 1 || a.getKey() == null || !shares.containsKey(a.getKey())) unnamed = unnamed.add(a.getValue());
            else if (a.getKey().equals(bookingId)) own = own.add(a.getValue());
        }
        return own.add(shares.size() <= 1 ? unnamed : part(unnamed, shares, bookingId));
    }
}
