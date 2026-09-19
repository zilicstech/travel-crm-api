package com.voyra.crm.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class FinancialYearTest {

    @Test
    void aprilFirstStartsTheNewFinancialYear() {
        assertThat(FinancialYear.of(LocalDate.of(2026, 4, 1))).isEqualTo("2026-27");
    }

    @Test
    void marchThirtyFirstIsStillThePreviousFinancialYear() {
        assertThat(FinancialYear.of(LocalDate.of(2027, 3, 31))).isEqualTo("2026-27");
    }

    @Test
    void midYearDateResolvesCorrectly() {
        assertThat(FinancialYear.of(LocalDate.of(2026, 9, 19))).isEqualTo("2026-27");
    }

    @Test
    void januaryIsInThePreviousFinancialYear() {
        assertThat(FinancialYear.of(LocalDate.of(2027, 1, 15))).isEqualTo("2026-27");
    }
}
