package com.voyra.crm.util;

import java.time.LocalDate;
import java.time.ZoneId;

/** India's fiscal year: 1 April to 31 March, rendered "yyyy-yy" (e.g. "2026-27"). */
public final class FinancialYear {

    private FinancialYear() {
    }

    /**
     * Today in India, where every agency trades. The server may run in UTC, so a bare {@code LocalDate.now()}
     * is still yesterday from 00:00 to 05:30 IST and would refuse a document dated today.
     */
    public static LocalDate today() {
        return LocalDate.now(ZoneId.of("Asia/Kolkata"));
    }

    public static String of(LocalDate date) {
        int startYear = date.getMonthValue() >= 4 ? date.getYear() : date.getYear() - 1;
        int endYearShort = (startYear + 1) % 100;
        return "%d-%02d".formatted(startYear, endYearShort);
    }
}
