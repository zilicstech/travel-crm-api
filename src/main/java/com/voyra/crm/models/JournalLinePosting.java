package com.voyra.crm.models;

import java.math.BigDecimal;

/**
 * One line of a {@link JournalPosting}. Exactly one of {@code debitAmount}/{@code creditAmount}
 * should be non-zero; {@code JournalService#post} rejects a line where both are.
 */
public record JournalLinePosting(
        String accountCode,
        String partyType,
        String partyId,
        String currencyCode,
        BigDecimal fxRateToInr,
        BigDecimal debitAmount,
        BigDecimal creditAmount,
        String narration
) {
    public static JournalLinePosting debit(String accountCode, BigDecimal amountInr, String narration) {
        return new JournalLinePosting(accountCode, null, null, "INR", BigDecimal.ONE, amountInr, BigDecimal.ZERO, narration);
    }

    public static JournalLinePosting credit(String accountCode, BigDecimal amountInr, String narration) {
        return new JournalLinePosting(accountCode, null, null, "INR", BigDecimal.ONE, BigDecimal.ZERO, amountInr, narration);
    }

    public static JournalLinePosting debitParty(String accountCode, String partyType, String partyId,
                                                 BigDecimal amountInr, String narration) {
        return new JournalLinePosting(accountCode, partyType, partyId, "INR", BigDecimal.ONE, amountInr, BigDecimal.ZERO, narration);
    }

    public static JournalLinePosting creditParty(String accountCode, String partyType, String partyId,
                                                  BigDecimal amountInr, String narration) {
        return new JournalLinePosting(accountCode, partyType, partyId, "INR", BigDecimal.ONE, BigDecimal.ZERO, amountInr, narration);
    }
}
