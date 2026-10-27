package com.voyra.crm.security;

import org.springframework.stereotype.Component;

import java.util.function.LongSupplier;

/** Caps public KYC uploads at {@value #LIMIT} per caller per 10 minutes, so a stolen upload token cannot fill storage. */
@Component
public class KycUploadRateLimiter extends FixedWindowRateLimiter {

    static final int LIMIT = 30;
    static final long WINDOW_MS = 10 * 60_000;

    public KycUploadRateLimiter() {
        this(System::currentTimeMillis);
    }

    KycUploadRateLimiter(LongSupplier clock) {
        super(LIMIT, WINDOW_MS, "Too many uploads from this connection - please wait a few minutes and try again.", clock);
    }
}
