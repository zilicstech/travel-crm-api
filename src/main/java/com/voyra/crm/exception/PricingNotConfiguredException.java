package com.voyra.crm.exception;

/** No approved rate is in force for the agency and product: Book is blocked (409 PRICING_NOT_CONFIGURED). */
public class PricingNotConfiguredException extends RuntimeException {

    public PricingNotConfiguredException() {
        super("Booking is not enabled for your agency yet - Voyra has not set your rates");
    }
}
