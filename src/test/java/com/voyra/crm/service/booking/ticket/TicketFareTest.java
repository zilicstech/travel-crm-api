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
    void markupGoesIntoOtherCharges() {
        List<FareLine> lines = TicketFare.lines(d("5000"), d("4000"), d("500"), Map.of("YQ", d("300")));
        assertThat(lines).extracting(FareLine::label).containsExactly("Base fare", "YQ", "Other charges");
        assertThat(lines).extracting(FareLine::amount).map(BigDecimal::toPlainString).containsExactly("4000.00", "300.00", "1200.00");
        assertThat(sum(lines)).isEqualByComparingTo("5500");
        assertThat(TicketFare.total(d("5000"), d("500"))).isEqualByComparingTo("5500").hasScaleOf(2);
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
}
