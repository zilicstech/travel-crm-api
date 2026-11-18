package com.voyra.crm.service.booking.ticket;

import com.voyra.crm.exception.BookingConflictException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketFareTest {

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    private static BigDecimal sum(List<FareLine> lines) {
        return lines.stream().map(FareLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void markupGivesOneTaxLineSoNoResidualRevealsIt() {
        List<FareLine> lines = TicketFare.lines(d("5000"), d("4000"), d("500"), Map.of("YQ", d("300")));
        assertThat(lines).extracting(FareLine::label).containsExactly("Base fare", "Taxes, fees and charges");
        assertThat(lines).extracting(FareLine::amount).map(BigDecimal::toPlainString).containsExactly("4000.00", "1500.00");
        assertThat(sum(lines)).isEqualByComparingTo("5500");
        assertThat(TicketFare.total(d("5000"), d("500"))).isEqualByComparingTo("5500").hasScaleOf(2);
    }

    @Test
    void noMarkupKeepsNamedTaxesAndTheResidual() {
        List<FareLine> lines = TicketFare.lines(d("5000"), d("4000"), BigDecimal.ZERO, Map.of("YQ", d("300")));
        assertThat(lines).extracting(FareLine::label).containsExactly("Base fare", "YQ", "Other charges");
        assertThat(lines).extracting(FareLine::amount).map(BigDecimal::toPlainString).containsExactly("4000.00", "300.00", "700.00");
        assertThat(sum(lines)).isEqualByComparingTo("5000");
    }

    @Test
    void zeroOtherChargesIsNotPrinted() {
        List<FareLine> lines = TicketFare.lines(d("5000"), d("4000"), null, Map.of("YQ", d("600"), "K3", d("400")));
        assertThat(lines).extracting(FareLine::label).containsExactlyInAnyOrder("Base fare", "YQ", "K3");
        assertThat(sum(lines)).isEqualByComparingTo("5000");
    }

    @Test
    void noBreakdownGivesOneTaxLine() {
        List<FareLine> lines = TicketFare.lines(d("5000"), d("4000"), null, Map.of());
        assertThat(lines).extracting(FareLine::label).containsExactly("Base fare", "Taxes, fees and charges");
        assertThat(lines.get(1).amount()).isEqualByComparingTo("1000");
        assertThat(TicketFare.total(d("5000"), null)).isEqualByComparingTo("5000");
    }

    @Test
    void negativeResidualFallsBackToOneLine() {
        List<FareLine> lines = TicketFare.lines(d("5000"), d("4000"), BigDecimal.ZERO, Map.of("YQ", d("1500")));
        assertThat(lines).extracting(FareLine::label).containsExactly("Base fare", "Taxes, fees and charges");
        assertThat(sum(lines)).isEqualByComparingTo("5000");
    }

    @Test
    void missingBaseRefuses() {
        assertThatThrownBy(() -> TicketFare.lines(d("5000"), null, BigDecimal.ZERO, Map.of()))
                .isInstanceOfSatisfying(BookingConflictException.class, e -> assertThat(e.getCode()).isEqualTo("TICKET_NOT_AVAILABLE"));
        assertThatThrownBy(() -> TicketFare.lines(null, d("1"), BigDecimal.ZERO, Map.of())).isInstanceOf(BookingConflictException.class);
    }

    @Test
    void baseAboveGrossRefuses() {
        assertThatThrownBy(() -> TicketFare.lines(d("4000"), d("4000.01"), BigDecimal.ZERO, Map.of())).isInstanceOf(BookingConflictException.class);
    }

    @Test
    void seatChargesPrintWithoutMarkupAndFoldWhenMarkupIsAdded() {
        // Named taxes + residual + seat charges
        List<FareLine> withNamed = TicketFare.lines(d("5600"), d("4000"), d("600"), BigDecimal.ZERO, Map.of("YQ", d("300")));
        assertThat(withNamed).extracting(FareLine::label).containsExactly("Base fare", "YQ", "Other charges", "Seat charges");
        assertThat(sum(withNamed)).isEqualByComparingTo("5600");

        // No named taxes + positive air taxes + seat charges
        List<FareLine> unnamed = TicketFare.lines(d("5600"), d("4000"), d("600"), null, Map.of());
        assertThat(unnamed).extracting(FareLine::label).containsExactly("Base fare", "Taxes, fees and charges", "Seat charges");
        assertThat(unnamed).extracting(FareLine::amount).map(BigDecimal::toPlainString).containsExactly("4000.00", "1000.00", "600.00");

        // Zero air taxes + seat charges -> omits 0.00 tax line
        List<FareLine> onlySeats = TicketFare.lines(d("4600"), d("4000"), d("600"), null, Map.of());
        assertThat(onlySeats).extracting(FareLine::label).containsExactly("Base fare", "Seat charges");

        // Markup > 0 -> folds seat charges into Taxes, fees and charges so markup is never exposed
        List<FareLine> markedUp = TicketFare.lines(d("5600"), d("4000"), d("600"), d("500"), Map.of("YQ", d("300")));
        assertThat(markedUp).extracting(FareLine::label).containsExactly("Base fare", "Taxes, fees and charges");
        assertThat(sum(markedUp)).isEqualByComparingTo("6100");

        // Seat total exceeding extras or negative seatTotal falls back cleanly
        List<FareLine> over = TicketFare.lines(d("5000"), d("4500"), d("800"), BigDecimal.ZERO, Map.of());
        assertThat(over).extracting(FareLine::label).containsExactly("Base fare", "Taxes, fees and charges");
        List<FareLine> nonPos = TicketFare.lines(d("5000"), d("4500"), d("-10"), BigDecimal.ZERO, Map.of());
        assertThat(nonPos).extracting(FareLine::label).containsExactly("Base fare", "Taxes, fees and charges");
        List<FareLine> nullSeats = TicketFare.lines(d("5000"), d("4500"), null, BigDecimal.ZERO, Map.of());
        assertThat(nullSeats).extracting(FareLine::label).containsExactly("Base fare", "Taxes, fees and charges");
        List<FareLine> zeroTaxesNoSeats = TicketFare.lines(d("4000"), d("4000"), BigDecimal.ZERO, BigDecimal.ZERO, Map.of());
        assertThat(zeroTaxesNoSeats).extracting(FareLine::label).containsExactly("Base fare", "Taxes, fees and charges");
    }
}
