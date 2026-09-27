package com.voyra.crm.util;

import com.voyra.crm.dto.InvoiceLineItemRequest;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.BookingPassenger;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.InvoiceServiceCategory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** unitPrice always stays fareAmount + taxAmount, so a captured split never changes what the customer is actually charged - only how the printed invoice breaks it down. */
class BookingInvoiceLineBuilderTest {

    private Booking booking() {
        return Booking.builder().id("B1").clientName("Arjun Mehta").type(BookingType.FLIGHT)
                .sellingPrice(new BigDecimal("100000.00")).build();
    }

    @Test
    void aPassengerWithNoTaxAmountBillsExactlyTheirFare() {
        BookingPassenger passenger = BookingPassenger.builder().id("P1").passengerName("Arjun Mehta")
                .fareAmount(new BigDecimal("138996.00")).taxAmount(BigDecimal.ZERO).build();

        List<InvoiceLineItemRequest> lines = BookingInvoiceLineBuilder.build(
                booking(), InvoiceServiceCategory.AIR_INTERNATIONAL, List.of(passenger), Map.of());

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).getUnitPrice()).isEqualByComparingTo("138996.00");
        assertThat(lines.get(0).getFareAmount()).isEqualByComparingTo("138996.00");
        assertThat(lines.get(0).getTaxAmount()).isEqualByComparingTo("0");
    }

    @Test
    void aPassengerWithATaxSplitBillsFarePlusTax() {
        BookingPassenger passenger = BookingPassenger.builder().id("P1").passengerName("Arjun Mehta")
                .fareAmount(new BigDecimal("95000.00")).taxAmount(new BigDecimal("5000.00")).build();

        List<InvoiceLineItemRequest> lines = BookingInvoiceLineBuilder.build(
                booking(), InvoiceServiceCategory.AIR_INTERNATIONAL, List.of(passenger), Map.of());

        assertThat(lines.get(0).getUnitPrice()).isEqualByComparingTo("100000.00");
        assertThat(lines.get(0).getFareAmount()).isEqualByComparingTo("95000.00");
        assertThat(lines.get(0).getTaxAmount()).isEqualByComparingTo("5000.00");
    }

    @Test
    void aWholeBookingFallbackLineCarriesNoFareSplit() {
        List<InvoiceLineItemRequest> lines = BookingInvoiceLineBuilder.build(
                booking(), InvoiceServiceCategory.PACKAGE, List.of(), Map.of());

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).getUnitPrice()).isEqualByComparingTo("100000.00");
        assertThat(lines.get(0).getFareAmount()).isNull();
        assertThat(lines.get(0).getTaxAmount()).isNull();
    }
}
