package com.voyra.crm.service;

import com.voyra.crm.dto.BookingDocumentCreateRequest;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.BookingDocument;
import com.voyra.crm.enums.BookingDocumentType;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.MarkupMode;
import com.voyra.crm.repository.BookingDocumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingDocumentServiceTest {

    @Mock private BookingDocumentRepository bookingDocumentRepository;
    @Mock private BookingService bookingService;
    @Mock private LeadTimelineService leadTimelineService;
    @Mock private FileStorageService fileStorageService;
    @Mock private AuditService auditService;
    @Mock private InvoiceLineSource invoiceLineSource;
    @Mock private com.voyra.crm.repository.MemberRepository memberRepository;
    @org.mockito.Spy
    private com.voyra.crm.service.BaseCurrency baseCurrency = new com.voyra.crm.service.BaseCurrency(null);

    @InjectMocks private BookingDocumentService service;

    @org.junit.jupiter.api.BeforeEach
    void nothingInvoiced() {
        org.mockito.Mockito.lenient().when(bookingService.invoiced(any()))
                .thenReturn(new InvoiceLineSource.Invoiced(BigDecimal.ZERO, java.util.Set.of(), null));
        org.mockito.Mockito.lenient().when(bookingService.draftVoucherIds(any())).thenReturn(java.util.Set.of());
    }

    private final Booking booking = Booking.builder().id("B1").bookingStatus(BookingStatus.CONFIRMED)
            .sellingPrice(new BigDecimal("4400")).netCost(new BigDecimal("4000")).build();
    private final BookingDocument voucher = BookingDocument.builder().id("D1").bookingId("B1").docType(BookingDocumentType.E_TICKET)
            .referenceNumber("PNR1").netCost(new BigDecimal("4000")).sellingPrice(new BigDecimal("4400"))
            .markupMode(MarkupMode.PERCENT).markupValue(new BigDecimal("10")).build();

    private BookingDocumentCreateRequest edit(String cost, String markup) {
        BookingDocumentCreateRequest r = new BookingDocumentCreateRequest();
        r.setDocType(BookingDocumentType.E_TICKET);
        r.setReferenceNumber("PNR2");
        r.setNetCost(new BigDecimal(cost));
        r.setMarkupMode(MarkupMode.PERCENT);
        r.setMarkupValue(new BigDecimal(markup));
        return r;
    }

    private void stub() {
        when(bookingService.lockAccessibleBooking("B1")).thenReturn(booking);
        when(bookingDocumentRepository.findByIdAndBookingId("D1", "B1")).thenReturn(Optional.of(voucher));
    }

    @Test
    void editingOnlyTheReferenceLeavesTheMoneyAlone() {
        stub();

        service.updateDocument("B1", "D1", edit("4000", "10"));

        assertThat(voucher.getReferenceNumber()).isEqualTo("PNR2");
        verify(bookingService, never()).reverseVoucher(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(bookingService, never()).applyVoucher(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void editingTheCostTakesTheOldMoneyBackAndBooksTheNew() {
        stub();
        when(bookingService.invoicedPrice("B1")).thenReturn(BigDecimal.ZERO);

        service.updateDocument("B1", "D1", edit("5000", "10"));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(bookingService);
        order.verify(bookingService).reverseVoucher(booking, voucher, false);
        order.verify(bookingService).applyVoucher(booking, voucher, null, false);
        assertThat(voucher.getNetCost()).isEqualByComparingTo("5000");
    }

    private void broken_down() {
        voucher.setCharges(new java.util.LinkedHashMap<>(java.util.Map.of("basic", new BigDecimal("3000"), "yq", new BigDecimal("1000"))));
        BookingService.applyCommission(voucher, MarkupMode.PERCENT, new BigDecimal("5"), null);
    }

    /** The agent's edit keeps the accountant's breakdown and commission while the purchase price stays. */
    @Test
    void anEditAtTheSamePriceKeepsTheAccountantsBreakdown() {
        stub();
        broken_down();

        service.updateDocument("B1", "D1", edit("4000", "10"));

        assertThat(voucher.getCharges()).containsKeys("basic", "yq");
        assertThat(voucher.getCommission()).isEqualByComparingTo("150");
        verify(bookingService, never()).applyVoucher(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void aNewPurchasePriceClearsTheBreakdownAndCommission() {
        stub();
        broken_down();
        when(bookingService.invoicedPrice("B1")).thenReturn(BigDecimal.ZERO);

        service.updateDocument("B1", "D1", edit("5000", "10"));

        assertThat(voucher.getCharges()).isNull();
        assertThat(voucher.getCommission()).isEqualByComparingTo("0");
        verify(bookingService).applyVoucher(booking, voucher, null, false);
    }

    private com.voyra.crm.dto.VoucherChargesRequest charges(String basic, String yq) {
        com.voyra.crm.dto.VoucherChargesRequest r = new com.voyra.crm.dto.VoucherChargesRequest();
        r.setCharges(new java.util.HashMap<>(java.util.Map.of("basic", new BigDecimal(basic), "yq", new BigDecimal(yq))));
        r.setCommissionMode(MarkupMode.PERCENT);
        r.setCommissionValue(new BigDecimal("5"));
        return r;
    }

    /** The accountant's breakdown must add up to the agent's purchase price; the commission goes through the booking. */
    @Test
    void theAccountantsBreakdownMustAddUpToThePurchasePrice() {
        stub();

        assertThatThrownBy(() -> service.setCharges("B1", "D1", charges("3000", "900")))
                .hasMessage("The breakdown adds up to 3900.00; the voucher's purchase price is 4000.00");

        service.setCharges("B1", "D1", charges("3000", "1000"));

        assertThat(voucher.getCharges()).containsKeys("basic", "yq");
        assertThat(voucher.getCommission()).isEqualByComparingTo("150");
        assertThat(voucher.getSupplierTds()).isEqualByComparingTo("3");
        assertThat(voucher.getNetCost()).isEqualByComparingTo("4000");
        verify(bookingService).changeCommission(org.mockito.ArgumentMatchers.eq(booking), org.mockito.ArgumentMatchers.eq(voucher),
                org.mockito.ArgumentMatchers.argThat(p -> p.getCommission().compareTo(new BigDecimal("150")) == 0));
        verify(invoiceLineSource).refreshDraftCharges(voucher);
    }

    @Test
    void anInvoicedVouchersBreakdownCannotChange() {
        stub();
        when(bookingService.invoiced("B1")).thenReturn(new InvoiceLineSource.Invoiced(new BigDecimal("4400"), java.util.Set.of("D1"), null));

        assertThatThrownBy(() -> service.setCharges("B1", "D1", charges("3000", "1000"))).hasMessageContaining("already invoiced");

        // The same breakdown with a new commission: the bill isn't approved yet, so that may still change.
        broken_down();
        com.voyra.crm.dto.VoucherChargesRequest flat = charges("3000.00", "1000");
        flat.setCommissionMode(MarkupMode.FLAT);
        flat.setCommissionValue(new BigDecimal("100"));
        service.setCharges("B1", "D1", flat);
        assertThat(voucher.getCommission()).isEqualByComparingTo("100");
    }

    @Test
    void anEditCannotTakeThePriceBelowWhatIsInvoiced() {
        stub();
        when(bookingService.invoicedPrice("B1")).thenReturn(new BigDecimal("4400"));
        booking.setSellingPrice(new BigDecimal("3300"));

        assertThatThrownBy(() -> service.updateDocument("B1", "D1", edit("3000", "10"))).hasMessageContaining("already invoiced");
    }

    @Test
    void aVoucherBoughtInAedIsCostedInBaseAndKeepsWhatWasTyped() {
        stub();
        when(bookingService.invoicedPrice("B1")).thenReturn(BigDecimal.ZERO);
        BookingDocumentCreateRequest r = edit("0", "0");
        r.setNetCost(null);
        r.setPurchaseCurrency("aed");
        r.setPurchasePrice(new BigDecimal("1000"));
        r.setPurchaseRate(new BigDecimal("22.85"));
        r.setMarkupMode(MarkupMode.FLAT);
        r.setMarkupValue(null);
        r.setMarkupCurrency("AED");
        r.setMarkupInput(new BigDecimal("100"));
        r.setMarkupRate(new BigDecimal("22.85"));

        service.updateDocument("B1", "D1", r);

        assertThat(voucher.getNetCost()).isEqualByComparingTo("22850.00");
        assertThat(voucher.getMarkupValue()).isEqualByComparingTo("2285.00");
        assertThat(voucher.getPurchaseCurrency()).isEqualTo("AED");
        assertThat(voucher.getPurchasePrice()).isEqualByComparingTo("1000");
        assertThat(voucher.getPurchaseRate()).isEqualByComparingTo("22.85");
        assertThat(voucher.getMarkupCurrency()).isEqualTo("AED");
        verify(bookingService).applyVoucher(booking, voucher, null, false);
    }

    @Test
    void aNewRateAloneRebooksTheVouchersMoney() {
        voucher.setPurchaseCurrency("AED");
        voucher.setPurchasePrice(new BigDecimal("1000"));
        voucher.setPurchaseRate(new BigDecimal("4"));
        voucher.setNetCost(new BigDecimal("4000"));
        stub();
        when(bookingService.invoicedPrice("B1")).thenReturn(BigDecimal.ZERO);
        BookingDocumentCreateRequest r = edit("4000", "10");
        r.setPurchaseCurrency("AED");
        r.setPurchasePrice(new BigDecimal("1000"));
        r.setPurchaseRate(new BigDecimal("4.1"));

        service.updateDocument("B1", "D1", r);

        verify(bookingService).reverseVoucher(booking, voucher, false);
        assertThat(voucher.getNetCost()).isEqualByComparingTo("4100.00");
        assertThat(voucher.getPurchaseRate()).isEqualByComparingTo("4.1");
    }

    @Test
    void aForeignPriceWithoutARateIsRefused() {
        stub();
        BookingDocumentCreateRequest r = edit("0", "10");
        r.setPurchaseCurrency("AED");
        r.setPurchasePrice(new BigDecimal("1000"));

        assertThatThrownBy(() -> service.updateDocument("B1", "D1", r)).hasMessageContaining("Enter the AED rate");
        verify(bookingService, never()).applyVoucher(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    /** An invoiced voucher is locked before anything about it changes. */
    @Test
    void anInvoicedVoucherCannotBeEdited() {
        stub();
        org.mockito.Mockito.doThrow(new IllegalStateException("This voucher is already invoiced - it can't be changed or removed"))
                .when(bookingService).assertNotInvoiced(voucher);

        assertThatThrownBy(() -> service.updateDocument("B1", "D1", edit("4000", "10"))).hasMessageContaining("already invoiced");
        assertThat(voucher.getReferenceNumber()).isEqualTo("PNR1");
    }

    @Test
    void theVouchersTravelDetailsAreKeptForItsBookingType() {
        stub();
        booking.setType(com.voyra.crm.enums.BookingType.FLIGHT);
        BookingDocumentCreateRequest r = edit("4000", "10");
        r.setDocType(BookingDocumentType.OTHER);
        r.setDetails(java.util.Map.of("airline", "IndiGo", "hotelName", "dropped"));

        service.updateDocument("B1", "D1", r);

        assertThat(voucher.getDetails()).containsExactly(java.util.Map.entry("airline", "IndiGo"));
    }

    // ------------------------------------------------------------------ an air ticket's travellers and flights

    private final Booking flight = Booking.builder().id("F1").clientId("C1").type(com.voyra.crm.enums.BookingType.FLIGHT)
            .bookingStatus(BookingStatus.CONFIRMED).sellingPrice(BigDecimal.ZERO).netCost(BigDecimal.ZERO).build();

    private BookingDocumentCreateRequest ticket(String... memberIds) {
        BookingDocumentCreateRequest r = new BookingDocumentCreateRequest();
        r.setDocType(BookingDocumentType.E_TICKET);
        r.setReferenceNumber("LVBJAY");
        r.setNetCost(new BigDecimal("1000"));
        r.setTravellers(java.util.Arrays.stream(memberIds).map(id -> com.voyra.crm.dto.VoucherTraveller.builder().memberId(id).build()).toList());
        r.setLegs(java.util.List.of(
                com.voyra.crm.dto.VoucherLeg.builder().from("dla").to("add").flightNumber("ET 962").travelDate(java.time.LocalDate.of(2026, 10, 31)).build(),
                com.voyra.crm.dto.VoucherLeg.builder().from("add").to("bom").flightNumber("ET 640").travelDate(java.time.LocalDate.of(2026, 10, 31)).build()));
        return r;
    }

    @org.junit.jupiter.api.AfterEach
    void signOut() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private static void signIn() {
        com.voyra.crm.security.CustomUserPrincipal agent = new com.voyra.crm.security.CustomUserPrincipal(
                "A1", "agent", com.voyra.crm.enums.UserType.AGENT, "T1");
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(agent, null, java.util.List.of()));
    }

    private static com.voyra.crm.entity.Member member(String id, String name, java.time.LocalDate dob) {
        return com.voyra.crm.entity.Member.builder().memberId(id).clientId("C1").name(name).dob(dob).isActive(true).build();
    }

    @Test
    void anAirTicketSnapshotsItsTravellersAndRunsItsDetailsFromItsFlights() {
        signIn();
        when(bookingService.lockAccessibleBooking("F1")).thenReturn(flight);
        when(memberRepository.findByMemberIdAndClientId("M1", "C1"))
                .thenReturn(Optional.of(member("M1", "Abhishek Kumar Singh", java.time.LocalDate.of(1990, 1, 1))));
        when(memberRepository.findByMemberIdAndClientId("M2", "C1"))
                .thenReturn(Optional.of(member("M2", "Aarav Singh", java.time.LocalDate.of(2020, 1, 1))));
        org.mockito.ArgumentCaptor<BookingDocument> saved = org.mockito.ArgumentCaptor.forClass(BookingDocument.class);

        service.addDocument("F1", ticket("M1", "M2"));

        verify(bookingDocumentRepository).save(saved.capture());
        BookingDocument d = saved.getValue();
        assertThat(d.getTravellers()).extracting(com.voyra.crm.dto.VoucherTraveller::getName).containsExactly("Abhishek Kumar Singh", "Aarav Singh");
        assertThat(d.getTravellers()).extracting(com.voyra.crm.dto.VoucherTraveller::getPaxType)
                .containsExactly(com.voyra.crm.enums.PaxType.ADULT, com.voyra.crm.enums.PaxType.CHILD);
        assertThat(d.getLegs()).extracting(com.voyra.crm.dto.VoucherLeg::getFrom).containsExactly("DLA", "ADD");
        assertThat(d.getDetails()).containsEntry("flightFrom", "DLA").containsEntry("flightTo", "BOM").containsEntry("journeyDate", "2026-10-31");
    }

    @Test
    void anAirTicketWithoutTravellersOrWithAnotherClientsMemberIsRefused() {
        signIn();
        when(bookingService.lockAccessibleBooking("F1")).thenReturn(flight);
        when(memberRepository.findByMemberIdAndClientId("X9", "C1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addDocument("F1", ticket()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Add the travellers on this ticket");
        assertThatThrownBy(() -> service.addDocument("F1", ticket("X9")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("That traveller isn't one of this client's members");
        verify(bookingDocumentRepository, never()).save(any());
    }

    @Test
    void anyOtherVoucherCarriesNoTravellersOrFlights() {
        signIn();
        when(bookingService.lockAccessibleBooking("F1")).thenReturn(flight);
        BookingDocumentCreateRequest insurance = ticket("M1");
        insurance.setDocType(BookingDocumentType.INSURANCE);
        org.mockito.ArgumentCaptor<BookingDocument> saved = org.mockito.ArgumentCaptor.forClass(BookingDocument.class);

        service.addDocument("F1", insurance);

        verify(bookingDocumentRepository).save(saved.capture());
        assertThat(saved.getValue().getTravellers()).isNull();
        assertThat(saved.getValue().getLegs()).isNull();
        verify(memberRepository, never()).findByMemberIdAndClientId(any(), any());
    }

    @Test
    void aVoucherSetsItsFlightsTripTypeUntilTheBookingIsOnAnInvoice() {
        signIn();
        when(bookingService.lockAccessibleBooking("F1")).thenReturn(flight);
        when(memberRepository.findByMemberIdAndClientId("M1", "C1"))
                .thenReturn(Optional.of(member("M1", "Abhishek Kumar Singh", null)));
        BookingDocumentCreateRequest international = ticket("M1");
        international.setInternationalTrip(true);

        service.addDocument("F1", international);
        assertThat(flight.getInternationalTrip()).isTrue();

        when(invoiceLineSource.draftOf("F1")).thenReturn(com.voyra.crm.entity.Invoice.builder().id("I1").build());
        BookingDocumentCreateRequest domestic = ticket("M1");
        domestic.setInternationalTrip(false);
        assertThatThrownBy(() -> service.addDocument("F1", domestic))
                .isInstanceOf(IllegalStateException.class).hasMessage("This booking is already on an invoice as International - its trip type can't change");
    }
}
