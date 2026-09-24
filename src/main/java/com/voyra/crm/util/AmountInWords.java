package com.voyra.crm.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * "Five Thousand Two Hundred Forty Five AED &amp; Thirteen Fills" - the amount-in-words line
 * the client's own invoices print under the total (see ACCOUNTING_REDESIGN_SPEC.md gap 6).
 *
 * <p>INR uses the Indian numbering system (Lakh/Crore); every other currency uses the
 * international one (Thousand/Million/Billion) - matching how each is actually spoken. The
 * currency/minor-unit name table below is the same ~18-currency list the source system's own
 * currency picker offers (spec §2.5); an unlisted code falls back to the raw ISO code and
 * "Cents" rather than failing to print.
 */
public final class AmountInWords {

    private AmountInWords() {
    }

    private record CurrencyWords(String major, String minor) {
    }

    private static final Map<String, CurrencyWords> CURRENCIES = Map.ofEntries(
            Map.entry("INR", new CurrencyWords("Rupees", "Paise")),
            Map.entry("USD", new CurrencyWords("US Dollars", "Cents")),
            Map.entry("AED", new CurrencyWords("AED", "Fills")),
            Map.entry("GBP", new CurrencyWords("British Pounds", "Pence")),
            Map.entry("EUR", new CurrencyWords("Euros", "Cents")),
            Map.entry("AUD", new CurrencyWords("Australian Dollars", "Cents")),
            Map.entry("SGD", new CurrencyWords("Singapore Dollars", "Cents")),
            Map.entry("THB", new CurrencyWords("Thai Baht", "Satang")),
            Map.entry("JPY", new CurrencyWords("Japanese Yen", "Sen")),
            Map.entry("CHF", new CurrencyWords("Swiss Francs", "Rappen")),
            Map.entry("CNY", new CurrencyWords("Chinese Yuan", "Fen")),
            Map.entry("NZD", new CurrencyWords("New Zealand Dollars", "Cents")),
            Map.entry("ZAR", new CurrencyWords("South African Rand", "Cents")),
            Map.entry("MYR", new CurrencyWords("Malaysian Ringgit", "Sen")),
            Map.entry("MUR", new CurrencyWords("Mauritian Rupees", "Cents")),
            Map.entry("KES", new CurrencyWords("Kenyan Shilling", "Cents")),
            Map.entry("RUB", new CurrencyWords("Russian Rubles", "Kopeks")),
            Map.entry("BDT", new CurrencyWords("Bangladeshi Taka", "Poisha")),
            Map.entry("LKR", new CurrencyWords("Sri Lankan Rupees", "Cents"))
    );

    private static final String[] ONES = {
            "", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten",
            "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen",
            "Eighteen", "Nineteen"
    };
    private static final String[] TENS = {
            "", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"
    };

    /** e.g. {@code "Five Thousand Two Hundred Forty Five AED & Thirteen Fills"}. */
    public static String forAmount(BigDecimal amount, String currencyCode) {
        BigDecimal scaled = (amount == null ? BigDecimal.ZERO : amount).setScale(2, RoundingMode.HALF_UP);
        long whole = scaled.longValue();
        int minorUnits = scaled.subtract(BigDecimal.valueOf(whole)).movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP).intValue();

        CurrencyWords words = CURRENCIES.getOrDefault(currencyCode,
                new CurrencyWords(currencyCode == null ? "" : currencyCode, "Cents"));
        boolean indian = "INR".equals(currencyCode);

        StringBuilder sb = new StringBuilder();
        sb.append(whole == 0 ? "Zero" : (indian ? spellIndian(whole) : spellInternational(whole)));
        sb.append(' ').append(words.major());
        if (minorUnits > 0) {
            sb.append(" & ").append(spellBelowThousand(minorUnits)).append(' ').append(words.minor());
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- Indian grouping (Lakh/Crore)

    private static String spellIndian(long n) {
        if (n == 0) return "Zero";
        StringBuilder sb = new StringBuilder();
        long crore = n / 10_000_000; n %= 10_000_000;
        long lakh = n / 100_000; n %= 100_000;
        long thousand = n / 1_000; n %= 1_000;
        long rest = n;

        if (crore > 0) sb.append(spellBelowThousand(crore)).append(" Crore ");
        if (lakh > 0) sb.append(spellBelowThousand(lakh)).append(" Lakh ");
        if (thousand > 0) sb.append(spellBelowThousand(thousand)).append(" Thousand ");
        if (rest > 0) sb.append(spellBelowThousand(rest)).append(' ');
        return sb.toString().trim();
    }

    // ---------------------------------------------------------------- International grouping

    private static String spellInternational(long n) {
        if (n == 0) return "Zero";
        StringBuilder sb = new StringBuilder();
        long billion = n / 1_000_000_000; n %= 1_000_000_000;
        long million = n / 1_000_000; n %= 1_000_000;
        long thousand = n / 1_000; n %= 1_000;
        long rest = n;

        if (billion > 0) sb.append(spellBelowThousand(billion)).append(" Billion ");
        if (million > 0) sb.append(spellBelowThousand(million)).append(" Million ");
        if (thousand > 0) sb.append(spellBelowThousand(thousand)).append(" Thousand ");
        if (rest > 0) sb.append(spellBelowThousand(rest)).append(' ');
        return sb.toString().trim();
    }

    /** 0-999. */
    private static String spellBelowThousand(long n) {
        StringBuilder sb = new StringBuilder();
        if (n >= 100) {
            sb.append(ONES[(int) (n / 100)]).append(" Hundred ");
            n %= 100;
        }
        if (n >= 20) {
            sb.append(TENS[(int) (n / 10)]).append(' ');
            n %= 10;
        }
        if (n > 0) {
            sb.append(ONES[(int) n]).append(' ');
        }
        return sb.toString().trim();
    }
}
