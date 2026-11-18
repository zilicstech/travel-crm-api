package com.voyra.crm.service.booking.ticket;

import com.voyra.crm.exception.BookingConflictException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fare lines printed on the customer ticket. With a markup the taxes print as one line, because any named
 * breakdown would leave a residual equal to the markup; without one the supplier's named taxes are shown.
 */
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
        return lines(gross, base, BigDecimal.ZERO, markup, namedTaxes);
    }

    public static List<FareLine> lines(BigDecimal gross, BigDecimal base, BigDecimal seatTotal, BigDecimal markup,
                                       Map<String, BigDecimal> namedTaxes) {
        if (gross == null || base == null || base.compareTo(gross) > 0) {
            throw new BookingConflictException("TICKET_NOT_AVAILABLE", "This booking has no fare breakdown, so a ticket cannot be made", Map.of());
        }
        BigDecimal baseFare = money(base);
        BigDecimal seats = seatTotal == null || seatTotal.signum() <= 0 ? BigDecimal.ZERO : money(seatTotal);
        BigDecimal extras = total(gross, markup).subtract(baseFare);
        List<FareLine> lines = new ArrayList<>();
        lines.add(new FareLine("Base fare", baseFare));
        boolean hasMarkup = markup != null && markup.signum() > 0;
        if (hasMarkup || seats.compareTo(extras) > 0) {
            lines.add(new FareLine("Taxes, fees and charges", extras));
            return lines;
        }
        BigDecimal airTaxes = extras.subtract(seats);
        BigDecimal named = namedTaxes.values().stream().map(TicketFare::money).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal other = airTaxes.subtract(named);
        if (namedTaxes.isEmpty() || other.signum() < 0) {
            if (airTaxes.signum() > 0 || seats.signum() == 0) {
                lines.add(new FareLine("Taxes, fees and charges", airTaxes));
            }
        } else {
            namedTaxes.forEach((code, amount) -> lines.add(new FareLine(code, money(amount))));
            if (other.signum() != 0) {
                lines.add(new FareLine("Other charges", other));
            }
        }
        if (seats.signum() > 0) {
            lines.add(new FareLine("Seat charges", seats));
        }
        return lines;
    }
}
