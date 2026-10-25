package com.voyra.crm.service.booking;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** Binds {@code app.flight-booking.*}: the mock's regression switches and the poller's give-up window. */
@Data
@Component
@ConfigurationProperties(prefix = "app.flight-booking")
public class FlightBookingProviderConfig {

    private String provider = "mock";
    private Mock mock = new Mock();
    private Poll poll = new Poll();

    @Data
    public static class Mock {
        /** Percent added to the reviewed total fare, to exercise the re-confirm path. */
        private BigDecimal repricePct = BigDecimal.ZERO;
        private boolean fareExpired;
        /** book() answers PENDING and bookingDetails() PENDING this many times, then TICKETED. */
        private int pendingPolls;
        /** book() times out, but the supplier did ticket it: bookingDetails() says TICKETED. */
        private boolean bookTimeout;
        /** book() answers a clean FAILED ("Mock: fare sold out"). */
        private boolean bookFail;
    }

    @Data
    public static class Poll {
        private int maxMinutes = 30;
    }
}
