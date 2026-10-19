package com.voyra.crm.security;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Round 2, R2-8: five inquiries a minute per caller, then 429 until the minute is up. */
class InquiryRateLimiterTest {

    @Test
    void theSixthPostInAMinuteIsRefusedAndTheNextMinuteIsOpenAgain() {
        AtomicLong now = new AtomicLong(1_000_000);
        InquiryRateLimiter limiter = new InquiryRateLimiter(now::get);
        for (int i = 0; i < 5; i++) {
            limiter.check("1.2.3.4");
        }
        assertThatThrownBy(() -> limiter.check("1.2.3.4")).isInstanceOf(InquiryRateLimiter.TooManyInquiriesException.class);
        assertThatCode(() -> limiter.check("5.6.7.8")).doesNotThrowAnyException();

        now.addAndGet(InquiryRateLimiter.WINDOW_MS);
        assertThatCode(() -> limiter.check("1.2.3.4")).doesNotThrowAnyException();
    }
}
