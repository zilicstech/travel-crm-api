package com.voyra.crm.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Per-caller fixed-window counter shared by the public endpoints' limiters.
 * shortcut: in-memory and per instance (each API instance allows its own limit) - move to a shared store
 * (Redis / a DB table) when the API runs on more than one instance or abuse is seen.
 */
abstract class FixedWindowRateLimiter {

    private static final int SWEEP_ABOVE = 10_000;

    /** caller -> {window start ms, calls in that window} */
    private final Map<String, long[]> windows = new ConcurrentHashMap<>();
    private final int limit;
    private final long windowMs;
    private final String message;
    private final LongSupplier clock;

    FixedWindowRateLimiter(int limit, long windowMs, String message, LongSupplier clock) {
        this.limit = limit;
        this.windowMs = windowMs;
        this.message = message;
        this.clock = clock;
    }

    /** @throws InquiryRateLimiter.TooManyInquiriesException once the caller is over the limit in the current window */
    public void check(String caller) {
        long now = clock.getAsLong();
        if (windows.size() > SWEEP_ABOVE) {
            windows.values().removeIf(w -> now - w[0] >= windowMs);
        }
        long[] w = windows.compute(caller == null ? "" : caller, (k, old) ->
                old == null || now - old[0] >= windowMs ? new long[] {now, 1} : new long[] {old[0], old[1] + 1});
        if (w[1] > limit) {
            throw new InquiryRateLimiter.TooManyInquiriesException(message);
        }
    }
}
