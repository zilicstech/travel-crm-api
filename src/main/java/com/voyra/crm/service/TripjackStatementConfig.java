package com.voyra.crm.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Binds {@code app.tripjack-statement.columns.*}: the statement's header names, so a renamed column is a config change. */
@Data
@Component
@ConfigurationProperties(prefix = "app.tripjack-statement")
public class TripjackStatementConfig {

    private Columns columns = new Columns();

    @Data
    public static class Columns {
        private String bookingId = "booking_id";
        private String pnr = "pnr";
        private String type = "type";
        private String amount = "amount";
        private String commission = "commission";
        private String date = "date";
    }
}
