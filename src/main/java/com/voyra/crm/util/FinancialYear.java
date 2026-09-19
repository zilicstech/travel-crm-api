package com.voyra.crm.util;

import java.time.LocalDate;

/** India's fiscal year: 1 April to 31 March, rendered "yyyy-yy" (e.g. "2026-27"). */
public final class FinancialYear {

    private FinancialYear() {
    }

    public static String of(LocalDate date) {
        int startYear = date.getMonthValue() >= 4 ? date.getYear() : date.getYear() - 1;
        int endYearShort = (startYear + 1) % 100;
        return "%d-%02d".formatted(startYear, endYearShort);
    }
}
