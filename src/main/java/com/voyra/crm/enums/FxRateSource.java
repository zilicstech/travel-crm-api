package com.voyra.crm.enums;

/**
 * Where an invoice's locked {@code fx_rate_to_inr} came from. {@code DAILY_TABLE} is an exact
 * same-day hit on the public {@code exchange_rate} table; {@code STALE_TABLE} is a fallback to
 * the most recent prior day for that pair (ACCOUNTING_EXPANSION_ARCHITECTURE.md Rule 3.2.3) -
 * never silently defaulted to 1.0.
 */
public enum FxRateSource {
    INR_IDENTITY, AGENCY_DEFAULT, MANUAL, DAILY_TABLE, STALE_TABLE
}
