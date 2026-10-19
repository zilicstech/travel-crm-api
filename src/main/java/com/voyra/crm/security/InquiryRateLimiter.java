package com.voyra.crm.security;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Caps the public inquiry form at {@value #LIMIT} posts a minute per caller, so one script cannot
 * flood the Platform Admin's list (the honeypot only stops naive bots).
 * shortcut: in-memory and per instance (each API instance allows its own 5/min), keyed on the first
 * X-Forwarded-For entry, which a client can set - move to a shared store (Redis / a DB table) keyed
 * on the trusted proxy's client IP when the API runs on more than one instance or abuse is seen.
 */
@Component
public class InquiryRateLimiter {

    static final int LIMIT = 5;
    static final long WINDOW_MS = 60_000;
    private static final int SWEEP_ABOVE = 10_000;

    /** caller -> {window start ms, posts in that window} */
    private final Map<String, long[]> windows = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public InquiryRateLimiter() {
        this(System::currentTimeMillis);
    }

    InquiryRateLimiter(LongSupplier clock) {
        this.clock = clock;
    }

    /** @throws TooManyInquiriesException once the caller has posted {@value #LIMIT} times in the current minute */
    public void check(String caller) {
        long now = clock.getAsLong();
        if (windows.size() > SWEEP_ABOVE) {
            windows.values().removeIf(w -> now - w[0] >= WINDOW_MS);
        }
        long[] w = windows.compute(caller == null ? "" : caller, (k, old) ->
                old == null || now - old[0] >= WINDOW_MS ? new long[] {now, 1} : new long[] {old[0], old[1] + 1});
        if (w[1] > LIMIT) {
            throw new TooManyInquiriesException();
        }
    }

    /** The visitor's address: the first X-Forwarded-For hop when behind a proxy, else the socket's. */
    public static String callerOf(jakarta.servlet.http.HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
    }

    public static class TooManyInquiriesException extends RuntimeException {
        public TooManyInquiriesException() {
            super("Too many requests from this connection - please wait a minute and try again.");
        }
    }
}
