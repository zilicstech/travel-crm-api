package com.voyra.crm.dto;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class DtoRulesTest {

    @Test
    void anErrorWithoutAMessageGetsAGenericOne() {
        ApiErrorResponse body = ApiErrorResponse.of(null, "reason", "cause", 500, "/api/x");
        assertThat(body.getMessage()).isEqualTo("An error occurred");
        assertThat(body.isSuccess()).isFalse();
        assertThat(body.getStatus()).isEqualTo(500);
        assertThat(body.getPath()).isEqualTo("/api/x");
        assertThat(ApiErrorResponse.of("Nope", null, null, 400, "/").getMessage()).isEqualTo("Nope");
    }

    @Test
    void aReturnFlightMayNotLeaveBeforeTheOutbound() {
        FlightSearchQuery q = new FlightSearchQuery();
        assertThat(q.isReturnOnOrAfterDeparture()).as("nothing set").isTrue();
        q.setReturnDate(LocalDate.of(2026, 11, 1));
        assertThat(q.isReturnOnOrAfterDeparture()).as("no departure yet").isTrue();
        q.setDepartDate(LocalDate.of(2026, 11, 1));
        assertThat(q.isReturnOnOrAfterDeparture()).as("same day").isTrue();
        q.setDepartDate(LocalDate.of(2026, 11, 2));
        assertThat(q.isReturnOnOrAfterDeparture()).isFalse();
    }

    @Test
    void nonZeroAllowsMissingAndSignedAmountsButNotZero() {
        NonZero.Validator v = new NonZero.Validator();
        assertThat(v.isValid(null, null)).isTrue();
        assertThat(v.isValid(new BigDecimal("-1"), null)).isTrue();
        assertThat(v.isValid(new BigDecimal("0.00"), null)).isFalse();
    }
}
