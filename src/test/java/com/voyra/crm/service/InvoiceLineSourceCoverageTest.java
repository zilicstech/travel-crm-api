package com.voyra.crm.service;

import com.voyra.crm.dto.InvoiceLineItemRequest;
import com.voyra.crm.dto.VoucherLeg;
import com.voyra.crm.dto.VoucherTraveller;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.BookingDocument;
import com.voyra.crm.entity.BookingPassenger;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.entity.InvoiceLineItem;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.InvoiceDocumentType;
import com.voyra.crm.enums.InvoiceLifecycle;
import com.voyra.crm.enums.InvoiceServiceCategory;
import com.voyra.crm.repository.BookingDocumentRepository;
import com.voyra.crm.repository.BookingPassengerRepository;
import com.voyra.crm.repository.BookingSectorRepository;
import com.voyra.crm.repository.InvoiceLineItemRepository;
import com.voyra.crm.repository.InvoiceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvoiceLineSourceCoverageTest {

    @Mock InvoiceRepository invoiceRepository;
    @Mock InvoiceLineItemRepository invoiceLineItemRepository;
    @Mock BookingDocumentRepository bookingDocumentRepository;
    @Mock BookingPassengerRepository bookingPassengerRepository;
    @Mock BookingSectorRepository bookingSectorRepository;
    @InjectMocks InvoiceLineSource source;

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 1, 10, 0);

    private static InvoiceLineItem line(String invoiceId, String bookingId, String voucherId, String subtotal) {
        return InvoiceLineItem.builder().invoiceId(invoiceId).bookingId(bookingId).bookingDocumentId(voucherId)
                .lineSubtotal(subtotal == null ? null : new BigDecimal(subtotal)).build();
    }

    private static Booking booking(BookingType type, String selling) {
        return Booking.builder().id("B1").bookingNumber(1L).type(type).destination("Goa").clientName("Asha")
                .sellingPrice(new BigDecimal(selling)).netCost(BigDecimal.ZERO).build();
    }

    private static BookingDocument voucher(String id, String selling, String net) {
        return BookingDocument.builder().id(id).bookingId("B1").sellingPrice(new BigDecimal(selling)).netCost(new BigDecimal(net))
                .createdAt(T0.plusHours(1)).build();
    }

    private void noInvoices() {
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc("B1", InvoiceDocumentType.TAX_INVOICE, InvoiceLifecycle.CANCELLED))
                .thenReturn(List.of());
    }

    @Test
    void coversVouchersItNamesAndThoseAddedBeforeAnUnnamedInvoice() {
        InvoiceLineSource.Invoiced named = new InvoiceLineSource.Invoiced(BigDecimal.ZERO, Set.of("D1"), null);
        InvoiceLineSource.Invoiced unnamed = new InvoiceLineSource.Invoiced(BigDecimal.ZERO, Set.of(), T0);

        assertThat(named.covers(BookingDocument.builder().id("D1").build())).isTrue();
        assertThat(named.covers(BookingDocument.builder().id("D2").createdAt(T0).build())).isFalse();
        assertThat(named.covers(BookingDocument.builder().build())).isFalse();
        assertThat(unnamed.covers(BookingDocument.builder().id("D2").createdAt(T0).build())).isTrue();
        assertThat(unnamed.covers(BookingDocument.builder().id("D2").createdAt(T0.plusSeconds(1)).build())).isFalse();
        assertThat(unnamed.covers(BookingDocument.builder().id("D2").build())).isFalse();
    }

    @Test
    void invoicedSumsIssuedInvoicesInBaseAndTracksTheLatestUnnamedOne() {
        Invoice draft = Invoice.builder().id("I0").status(InvoiceLifecycle.DRAFT).build();
        Invoice inr = Invoice.builder().id("I1").bookingId("B1").status(InvoiceLifecycle.ISSUED).fxRateToInr(BigDecimal.ONE).issuedAt(T0).build();
        Invoice noRate = Invoice.builder().id("I2").bookingId("B1").status(InvoiceLifecycle.PAID).fxRateToInr(null).issuedAt(T0.plusDays(1)).build();
        Invoice usd = Invoice.builder().id("I3").bookingId("B1").status(InvoiceLifecycle.ISSUED).fxRateToInr(new BigDecimal("80")).issuedAt(T0.minusDays(1)).build();
        Invoice usdUnnamed = Invoice.builder().id("I4").bookingId("B9").status(InvoiceLifecycle.ISSUED).fxRateToInr(new BigDecimal("80")).build();
        Invoice olderUnnamed = Invoice.builder().id("I5").bookingId("B1").status(InvoiceLifecycle.ISSUED).issuedAt(T0.minusDays(5)).build();
        Invoice otherBooking = Invoice.builder().id("I6").bookingId("B7").status(InvoiceLifecycle.ISSUED).issuedAt(T0.plusDays(9)).build();
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc("B1", InvoiceDocumentType.TAX_INVOICE, InvoiceLifecycle.CANCELLED))
                .thenReturn(List.of(draft, inr, noRate, usd, usdUnnamed, olderUnnamed, otherBooking));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I5")).thenReturn(List.of(line("I5", null, null, "5.00")));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I6")).thenReturn(List.of(line("I6", null, null, "7.00")));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of(line("I1", null, null, "100.00"), line("I1", "B2", null, "999")));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I2")).thenReturn(List.of(line("I2", "B1", null, null)));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I3")).thenReturn(List.of(line("I3", "B1", "D1", "10.00"), line("I3", "B1", null, "1.00")));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I4")).thenReturn(List.of(line("I4", "B1", null, "2.00")));
        when(bookingDocumentRepository.findAllById(List.of("D1"))).thenReturn(List.of(
                BookingDocument.builder().id("D1").sellingPrice(new BigDecimal("805.00")).build(),
                BookingDocument.builder().id("D1x").sellingPrice(null).build()));

        InvoiceLineSource.Invoiced res = source.invoiced("B1");

        // 100 + 0 + (1 x 80 + 805) + 2 x 80 + 5
        assertThat(res.price()).isEqualByComparingTo("1150.00");
        assertThat(res.voucherIds()).containsExactly("D1");
        assertThat(res.unnamedAt()).isEqualTo(T0.plusDays(1));
        verify(invoiceLineItemRepository, never()).findByInvoiceIdOrderBySortOrderAsc("I0");
    }

    @Test
    void aBookingFullyBilledHasNoLinesLeft() {
        noInvoices();

        InvoiceLineSource.Lines lines = source.linesFor(booking(BookingType.HOTEL, "0"), InvoiceServiceCategory.HOTEL);

        assertThat(lines.lines()).isEmpty();
        assertThat(lines.passThrough()).isEqualByComparingTo("0");
        verifyNoInteractions(bookingDocumentRepository);
    }

    @Test
    void openVouchersThatMakeUpWhatIsLeftAreBilledOneLineEach() {
        noInvoices();
        Booking b = booking(BookingType.FLIGHT, "1500.00");
        BookingDocument d1 = voucher("D1", "1000.00", "900.00");
        d1.setCharges(Map.of("basic", new BigDecimal("900.00")));
        d1.setTravellers(List.of(VoucherTraveller.builder().name("PAX A").build(), VoucherTraveller.builder().name(" ").build()));
        d1.setReferenceNumber(" PNR1 ");
        d1.setDetails(Map.of("flightFrom", "BOM", "flightTo", "DEL", "journeyDate", "2026-12-20"));
        BookingDocument d2 = voucher("D2", "500.00", "450.00");
        d2.setTravellers(List.of());
        BookingDocument free = voucher("D3", "0", "0");
        when(bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of(d1, d2, free));
        when(bookingPassengerRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of(
                BookingPassenger.builder().passengerName("PAX B").build(), BookingPassenger.builder().passengerName(null).build()));

        InvoiceLineSource.Lines lines = source.linesFor(b, InvoiceServiceCategory.AIR_DOMESTIC);

        assertThat(lines.lines()).hasSize(2);
        InvoiceLineItemRequest first = lines.lines().get(0);
        assertThat(first.getBookingDocumentId()).isEqualTo("D1");
        assertThat(first.getDescription()).isEqualTo("BOM to DEL 20/12/26 (PNR1) - PAX A");
        assertThat(first.getCharges()).containsEntry("basic", new BigDecimal("900.00"));
        assertThat(first.getUnitPrice()).isEqualByComparingTo("1000.00");
        // A voucher with no travellers names the booking's passengers; no label falls back to the destination.
        assertThat(lines.lines().get(1).getDescription()).isEqualTo("Goa - PAX B");
        assertThat(lines.passThrough()).isEqualByComparingTo("1350.00");
    }

    @Test
    void aSingleVoucherOnABookingWithTravellersBillsEachTravellerWhenTheirFaresAddUp() {
        noInvoices();
        Booking b = booking(BookingType.HOTEL, "1000.00");
        when(bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of(voucher("D1", "1000.00", "800.00")));
        when(bookingPassengerRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of(
                BookingPassenger.builder().id("P1").passengerName("PAX A").fareAmount(new BigDecimal("600.00")).build(),
                BookingPassenger.builder().id("P2").passengerName("PAX B").fareAmount(new BigDecimal("300.00")).taxAmount(new BigDecimal("100.00")).build()));
        when(bookingSectorRepository.findByBookingPassengerIdInOrderBySortOrderAsc(List.of("P1", "P2"))).thenReturn(List.of());

        InvoiceLineSource.Lines lines = source.linesFor(b, InvoiceServiceCategory.HOTEL);

        assertThat(lines.lines()).hasSize(2).allSatisfy(l -> {
            assertThat(l.getBookingId()).isEqualTo("B1");
            assertThat(l.getBookingDocumentId()).isEqualTo("D1");
        });
        assertThat(lines.passThrough()).isEqualByComparingTo("800.00");
    }

    @Test
    void travellersWhoseFaresDoNotAddUpFallBackToTheVoucherLine() {
        noInvoices();
        Booking b = booking(BookingType.HOTEL, "1000.00");
        BookingDocument d = voucher("D1", "1000.00", "800.00");
        d.setDetails(Map.of("hotelName", "Taj", "hotelCheckIn", "not-a-date"));
        when(bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of(d));
        when(bookingPassengerRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of(
                BookingPassenger.builder().id("P1").passengerName("PAX A").fareAmount(new BigDecimal("700.00")).build()));
        when(bookingSectorRepository.findByBookingPassengerIdInOrderBySortOrderAsc(List.of("P1"))).thenReturn(List.of());

        InvoiceLineSource.Lines lines = source.linesFor(b, InvoiceServiceCategory.HOTEL);

        assertThat(lines.lines()).singleElement().satisfies(l -> {
            assertThat(l.getBookingDocumentId()).isEqualTo("D1");
            assertThat(l.getDescription()).isEqualTo("Taj not-a-date - PAX A");
        });
    }

    @Test
    void aSingleVoucherWithNoTravellersOrANonPassengerCategoryBillsTheVoucher() {
        noInvoices();
        Booking b = booking(BookingType.TRANSFER, "400.00");
        b.setDestination(null);
        when(bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of(voucher("D1", "400.00", "300.00")));
        when(bookingPassengerRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of());

        InvoiceLineSource.Lines transport = source.linesFor(b, InvoiceServiceCategory.TRANSPORT);
        InvoiceLineSource.Lines hotel = source.linesFor(b, InvoiceServiceCategory.HOTEL);

        assertThat(transport.lines()).singleElement().satisfies(l -> assertThat(l.getDescription()).isEqualTo("TRANSFER"));
        assertThat(hotel.lines()).singleElement().satisfies(l -> assertThat(l.getBookingDocumentId()).isEqualTo("D1"));
        verifyNoInteractions(bookingSectorRepository);
    }

    @Test
    void whatIsLeftOutsideTheVouchersIsOneLineForTheBooking() {
        Invoice earlier = Invoice.builder().id("I1").bookingId("B1").status(InvoiceLifecycle.ISSUED).fxRateToInr(BigDecimal.ONE).build();
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc("B1", InvoiceDocumentType.TAX_INVOICE, InvoiceLifecycle.CANCELLED))
                .thenReturn(List.of(earlier));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of(line("I1", null, "D1", "300.00")));
        Booking b = booking(BookingType.PACKAGE, "1000.00");
        b.setNetCost(new BigDecimal("900.00"));
        when(bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of(voucher("D1", "300.00", "200.00")));

        InvoiceLineSource.Lines lines = source.linesFor(b, InvoiceServiceCategory.PACKAGE);

        assertThat(lines.lines()).singleElement().satisfies(l -> {
            assertThat(l.getDescription()).isEqualTo("Services on BK00000001 - Goa");
            assertThat(l.getUnitPrice()).isEqualByComparingTo("700.00");
            assertThat(l.getBookingDocumentId()).isNull();
        });
        // unvouchered 900 - 200 = 700 passes through, capped at what is left
        assertThat(lines.passThrough()).isEqualByComparingTo("700.00");
    }

    @Test
    void openVouchersThatDoNotMakeUpWhatIsLeftAreBilledAsOneLine() {
        noInvoices();
        Booking b = booking(BookingType.HOTEL, "1000.00");
        when(bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of(voucher("D1", "300.00", "250.00")));

        InvoiceLineSource.Lines lines = source.linesFor(b, InvoiceServiceCategory.HOTEL);

        assertThat(lines.lines()).singleElement().satisfies(l -> assertThat(l.getUnitPrice()).isEqualByComparingTo("1000.00"));
        assertThat(lines.passThrough()).isEqualByComparingTo("250.00");
    }

    @Test
    void aLaterSingleVoucherAfterAnEarlierInvoiceIsBilledAsTheVoucher() {
        Invoice earlier = Invoice.builder().id("I1").bookingId("B1").status(InvoiceLifecycle.ISSUED).fxRateToInr(BigDecimal.ONE).build();
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc("B1", InvoiceDocumentType.TAX_INVOICE, InvoiceLifecycle.CANCELLED))
                .thenReturn(List.of(earlier));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of(line("I1", "B1", null, "300.00")));
        Booking b = booking(BookingType.HOTEL, "1000.00");
        when(bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of(voucher("D2", "700.00", "600.00")));
        when(bookingPassengerRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of());

        InvoiceLineSource.Lines lines = source.linesFor(b, InvoiceServiceCategory.HOTEL);

        assertThat(lines.lines()).singleElement().satisfies(l -> {
            assertThat(l.getBookingDocumentId()).isEqualTo("D2");
            assertThat(l.getUnitPrice()).isEqualByComparingTo("700.00");
        });
    }

    @Test
    void aLongDescriptionIsCutTo500AndABookingWithNoDestinationSaysSo() {
        noInvoices();
        Booking b = booking(BookingType.PACKAGE, "100.00");
        b.setDestination(null);
        when(bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of());

        assertThat(source.linesFor(b, InvoiceServiceCategory.PACKAGE).lines().get(0).getDescription()).isEqualTo("Services on BK00000001");

        BookingDocument longOne = voucher("D1", "100.00", "0");
        longOne.setDetails(Map.of("visaCountry", "X".repeat(600)));
        when(bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of(longOne, voucher("D2", "0", "0")));
        when(bookingPassengerRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of());
        b.setType(BookingType.FLIGHT);
        assertThat(source.linesFor(b, InvoiceServiceCategory.VISA).lines().get(0).getDescription()).hasSize(500);
    }

    @Test
    void draftsLookupsAndChargeRefreshTouchOnlyTheVouchersOwnLine() {
        Invoice draft = Invoice.builder().id("I1").bookingId("B1").build();
        when(invoiceRepository.findDraftsForBooking("B1")).thenReturn(List.of(draft));
        when(invoiceRepository.findDraftsForBooking("B2")).thenReturn(List.of());
        InvoiceLineItem own = line("I1", "B1", "D1", "100");
        InvoiceLineItem other = line("I1", null, "D2", "100");
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of(own, other, line("I1", "B1", null, "1")));

        assertThat(source.draftOf("B1")).isSameAs(draft);
        assertThat(source.draftOf("B2")).isNull();
        assertThat(source.draftVoucherIds("B1")).containsExactlyInAnyOrder("D1", "D2");

        BookingDocument v = BookingDocument.builder().id("D1").bookingId("B1").charges(Map.of("basic", new BigDecimal("50")))
                .purchaseRate(new BigDecimal("2")).netCost(new BigDecimal("100")).build();
        source.refreshDraftCharges(v);
        assertThat(own.getCharges()).containsEntry("basic", new BigDecimal("100.00"));
        verify(invoiceLineItemRepository).save(own);
    }

    @Test
    void aChargeRefreshSkipsADraftBillingTheVoucherOnSeveralLines() {
        Invoice draft = Invoice.builder().id("I1").bookingId("B1").build();
        when(invoiceRepository.findDraftsForBooking("B1")).thenReturn(List.of(draft));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of(
                line("I1", "B1", "D1", "50"), line("I1", "B1", "D1", "50")));

        source.refreshDraftCharges(BookingDocument.builder().id("D1").bookingId("B1").build());

        verify(invoiceLineItemRepository, never()).save(any());
    }

    @Test
    void bookingIdsOfPutsTheFirstBookingFirstWithoutDuplicates() {
        when(invoiceLineItemRepository.findBookingIdsByInvoiceId("I1")).thenReturn(List.of("B1", "B2"));
        when(invoiceLineItemRepository.findBookingIdsByInvoiceId("I2")).thenReturn(List.of("B3"));

        assertThat(source.bookingIdsOf(Invoice.builder().id("I1").bookingId("B1").build())).containsExactly("B1", "B2");
        assertThat(source.bookingIdsOf(Invoice.builder().id("I2").build())).containsExactly("B3");
    }

    @Test
    void voucherLabelsReadEveryKindOfVoucher() {
        Map<String, String> transfer = new HashMap<>();
        transfer.put("transferPickup", "Airport");
        transfer.put("transferDate", "2026-12-01");
        transfer.put("flightFrom", "BOM");
        assertThat(InvoiceLineSource.voucherLabel(BookingDocument.builder().details(transfer).referenceNumber("").build()))
                .isEqualTo("Airport 01/12/26");
        assertThat(InvoiceLineSource.voucherLabel(BookingDocument.builder().build())).isEmpty();
        assertThat(InvoiceLineSource.voucherLabel(BookingDocument.builder().legs(List.of()).referenceNumber("R1").build())).isEqualTo("(R1)");
        assertThat(InvoiceLineSource.voucherLabel(BookingDocument.builder().legs(List.of(
                VoucherLeg.builder().from("BOM").to(" ").build(), VoucherLeg.builder().from("X").to(null).build())).build())).isEmpty();
        Map<String, String> flightToOnly = Map.of("flightTo", "DEL");
        assertThat(InvoiceLineSource.voucherLabel(BookingDocument.builder().details(flightToOnly).build())).isEmpty();
    }
}
