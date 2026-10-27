package com.voyra.crm.models;

import com.voyra.crm.enums.WalletEntryType;

import java.math.BigDecimal;

/** Input to WalletService's single private posting path; amount is signed. Never serialized. */
public record WalletPosting(
        WalletEntryType type,
        BigDecimal amount,
        String idempotencyKey,
        String refType,
        String refId,
        String narration,
        String relatedEntryId
) {
}
