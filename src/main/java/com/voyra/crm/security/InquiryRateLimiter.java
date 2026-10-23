package com.voyra.crm.security;

import org.springframework.stereotype.Component;

import java.util.function.LongSupplier;

/**
 * Caps the public inquiry form (and agency-application create) at {@value #LIMIT} posts a minute per caller,
 * so one script cannot flood the Platform Admin's list (the honeypot only stops naive bots).
 */
@Component
public class InquiryRateLimiter extends FixedWindowRateLimiter {

    static final int LIMIT = 5;
    static final long WINDOW_MS = 60_000;
    static final String MESSAGE = "Too many requests from this connection - please wait a minute and try again.";

    public InquiryRateLimiter() {
        this(System::currentTimeMillis);
    }

    InquiryRateLimiter(LongSupplier clock) {
        super(LIMIT, WINDOW_MS, MESSAGE, clock);
    }

    /**
     * The visitor's address: the RIGHTMOST X-Forwarded-For entry - the hop our load balancer appends, which a
     * client cannot forge (anything the client sends lands to its left) - else the socket's.
     */
    public static String callerOf(jakarta.servlet.http.HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return request.getRemoteAddr();
        }
        String[] hops = forwarded.split(",");
        String last = hops[hops.length - 1].trim();
        return last.isEmpty() ? request.getRemoteAddr() : last;
    }

    /** Mapped to 429 by the global exception handler; shared by every public limiter. */
    public static class TooManyInquiriesException extends RuntimeException {
        public TooManyInquiriesException() {
            super(MESSAGE);
        }

        public TooManyInquiriesException(String message) {
            super(message);
        }
    }
}
