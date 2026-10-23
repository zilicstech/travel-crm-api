package com.voyra.crm.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    void callerIsTheRightmostForwardedHopSoAClientCannotSpoofIt() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.9");
        request.addHeader("X-Forwarded-For", "6.6.6.6, 203.0.113.7");

        assertThat(InquiryRateLimiter.callerOf(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void callerFallsBackToTheSocketWithoutAForwardedHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.9");

        assertThat(InquiryRateLimiter.callerOf(request)).isEqualTo("10.0.0.9");
    }

    @Test
    void uploadsAllowThirtyPerTenMinutesThenRefuse() {
        AtomicLong now = new AtomicLong(1_000_000);
        KycUploadRateLimiter limiter = new KycUploadRateLimiter(now::get);
        for (int i = 0; i < 30; i++) {
            limiter.check("1.2.3.4");
        }
        assertThatThrownBy(() -> limiter.check("1.2.3.4")).isInstanceOf(InquiryRateLimiter.TooManyInquiriesException.class);

        now.addAndGet(KycUploadRateLimiter.WINDOW_MS - 1);
        assertThatThrownBy(() -> limiter.check("1.2.3.4")).isInstanceOf(InquiryRateLimiter.TooManyInquiriesException.class);
        now.addAndGet(1);
        assertThatCode(() -> limiter.check("1.2.3.4")).doesNotThrowAnyException();
    }
}
