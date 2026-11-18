package com.voyra.crm.models;

/**
 * What a supplier's catalogue load saw, so the service can judge it before deleting anything: {@code expected} hotel
 * ids the supplier said it has, {@code fetched} hotels handed over, content calls failed out of made, and
 * {@code fatal} when the load could not know what it missed (a mapping page lost, a repeated page, throttled out).
 */
public record CatalogSummary(int expected, int fetched, int failedCalls, int totalCalls, String fatal) {

    public static final CatalogSummary EMPTY = new CatalogSummary(0, 0, 0, 0, null);
}
