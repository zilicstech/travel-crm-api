package com.voyra.crm.service.booking;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** Binds {@code app.hotel-booking.*}: the mock's regression switches (same meaning as {@code app.flight-booking.mock.*}) and the poller's give-up window. */
@Data
@Component
@ConfigurationProperties(prefix = "app.hotel-booking")
public class HotelBookingProviderConfig {

    private String provider = "mock";
    private Mock mock = new Mock();
    private FlightBookingProviderConfig.Poll poll = new FlightBookingProviderConfig.Poll();

    @Data
    public static class Mock {
        /** Percent added to the reviewed total, to exercise the re-confirm path. */
        private BigDecimal repricePct = BigDecimal.ZERO;
        private boolean fareExpired;
        /** book() answers PENDING and bookingDetails() PENDING this many times, then confirmed. */
        private int pendingPolls;
        /** book() times out, but the supplier did confirm it: bookingDetails() says confirmed. */
        private boolean bookTimeout;
        /** book() answers a clean FAILED ("Mock: room sold out"). */
        private boolean bookFail;
        /** cancel() times out, like a supplier that never answers: the booking stays CANCELLING until an admin resolves it. */
        private boolean cancelTimeout;
        /** cancel() is refused ("Mock: cancellation not allowed"). */
        private boolean cancelFail;
    }
}
