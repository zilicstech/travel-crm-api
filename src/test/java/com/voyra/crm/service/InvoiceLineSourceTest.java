package com.voyra.crm.service;

import com.voyra.crm.dto.VoucherLeg;
import com.voyra.crm.entity.BookingDocument;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceLineSourceTest {

    @Test
    void aReturnTripsLineNamesEveryStop() {
        BookingDocument ret = BookingDocument.builder().referenceNumber("EKPNR1")
                .details(Map.of("flightFrom", "BOM", "flightTo", "BOM", "journeyDate", "2026-12-18"))
                .legs(List.of(VoucherLeg.builder().from("BOM").to("DXB").travelDate(LocalDate.of(2026, 12, 18)).build(),
                        VoucherLeg.builder().from("DXB").to("BOM").travelDate(LocalDate.of(2026, 12, 24)).build()))
                .build();
        BookingDocument oneWay = BookingDocument.builder().referenceNumber("RET1")
                .details(Map.of("flightFrom", "BOM", "flightTo", "DEL", "journeyDate", "2026-12-20"))
                .legs(List.of(VoucherLeg.builder().from("BOM").to("DEL").travelDate(LocalDate.of(2026, 12, 20)).build()))
                .build();

        assertThat(InvoiceLineSource.voucherLabel(ret)).startsWith("BOM - DXB - BOM ");
        assertThat(InvoiceLineSource.voucherLabel(oneWay)).startsWith("BOM to DEL ");
    }
}
