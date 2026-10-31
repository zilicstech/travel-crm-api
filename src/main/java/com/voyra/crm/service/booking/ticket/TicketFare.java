package com.voyra.crm.service.booking.ticket;

import com.voyra.crm.exception.BookingConflictException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Fare lines printed on the customer ticket. The markup is folded into the tax lines and never shown on its own. */
public final class TicketFare {

    private TicketFare() {
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    public static BigDecimal total(BigDecimal gross, BigDecimal markup) {
        return gross.add(markup == null ? BigDecimal.ZERO : markup).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    public static List<FareLine> lines(BigDecimal gross, BigDecimal base, BigDecimal markup, Map<String, BigDecimal> namedTaxes) {
        if (gross == null || base == null || base.compareTo(gross) > 0) {
            throw new BookingConflictException("TICKET_NOT_AVAILABLE", "This booking has no fare breakdown, so a ticket cannot be made", Map.of());
        }
        BigDecimal baseFare = base.setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal taxes = total(gross, markup).subtract(baseFare);
        List<FareLine> lines = new ArrayList<>();
        lines.add(new FareLine("Base fare", baseFare));
        BigDecimal named = namedTaxes.values().stream().map(TicketFare::money).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal other = taxes.subtract(named);
        if (namedTaxes.isEmpty() || other.signum() < 0) {
            lines.add(new FareLine("Taxes, fees and charges", taxes));
            return lines;
        }
        namedTaxes.forEach((code, amount) -> lines.add(new FareLine(code, money(amount))));
        lines.add(new FareLine("Other charges", other));
        return lines;
    }
}
