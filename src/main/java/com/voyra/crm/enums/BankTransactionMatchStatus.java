package com.voyra.crm.enums;

/**
 * UNMATCHED - no candidate found yet. TIER1_MATCHED - exact match, auto-posted at import time
 * (Rule 4.3's tier 1). TIER2_CANDIDATE - a probabilistic candidate surfaced for human confirmation,
 * never auto-posted (Rule 4.3.1). CONFIRMED - a tier-2 candidate (or a manual pick) a human
 * confirmed, which is what actually triggered the posting. CATEGORIZED - a bank_match_rule applied
 * (Rule 4.3.3), either auto-posted or pre-filled pending confirmation depending on the rule.
 */
public enum BankTransactionMatchStatus {
    UNMATCHED, TIER1_MATCHED, TIER2_CANDIDATE, CONFIRMED, CATEGORIZED
}
