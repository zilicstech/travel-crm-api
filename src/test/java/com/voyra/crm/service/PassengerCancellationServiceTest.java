package com.voyra.crm.service;

import com.voyra.crm.dto.BookingDocumentCreateRequest;
import com.voyra.crm.dto.BookingResponse;
import com.voyra.crm.dto.PassengerCancelRequest;
import com.voyra.crm.dto.VoucherTraveller;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.BookingDocument;
import com.voyra.crm.enums.BookingDocumentType;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.MarkupMode;
import com.voyra.crm.exception.BookingConflictException;
import com.voyra.crm.repository.BookingDocumentRepository;
import com.voyra.crm.repository.BookingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PassengerCancellationServiceTest {

    @Mock private BookingService bookingService;
    @Mock private BookingDocumentService documentService;
    @Mock private BookingDocumentRepository documentRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private BookingActivityService activityService;
    @InjectMocks private PassengerCancellationService service;

    private Booking booking;
    private BookingDocument ticket;

    private static VoucherTraveller t(String id, String name) {
        return VoucherTraveller.builder().memberId(id).name(name).build();
    }

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static PassengerCancelRequest request(String... ids) {
        return PassengerCancelRequest.builder().travellerIds(List.of(ids)).sellingShare(d("10000")).purchaseShare(d("9000"))
                .airlineCharge(d("3000")).supplierCharge(d("200")).agencyFee(d("500")).reason(" Unwell ").build();
    }

    @BeforeEach
    void setUp() {
        booking = Booking.builder().id("B1").type(BookingType.FLIGHT).bookingStatus(BookingStatus.CONFIRMED).build();
        ticket = BookingDocument.builder().id("D1").bookingId("B1").docType(BookingDocumentType.E_TICKET).referenceNumber("PNR1")
                .netCost(d("27000")).sellingPrice(d("30000")).vendorId("V1")
                .travellers(new ArrayList<>(List.of(t("M1", "Asha"), t("M2", "Ravi"), t("M3", "Mira")))).build();
        given(bookingService.lockForVoucher("B1")).willReturn(booking);
        given(bookingService.invoiced("B1")).willReturn(new InvoiceLineSource.Invoiced(BigDecimal.ZERO, Set.of(), null));
        given(documentRepository.findByBookingIdOrderBySortOrderAsc("B1")).willReturn(List.of(
                BookingDocument.builder().id("D0").docType(BookingDocumentType.OTHER).build(),
                BookingDocument.builder().id("D9").docType(BookingDocumentType.E_TICKET).build(),
                ticket));
        given(documentRepository.findByIdAndBookingId("D1", "B1")).willReturn(Optional.of(ticket));
        given(bookingRepository.findById("B1")).willReturn(Optional.of(booking));
        given(bookingService.toResponsePublic(any())).willReturn(BookingResponse.builder().id("B1").build());
    }

    @Test
    void theTicketIsLoweredByTheSharesLessTheChargesAndItsTravellersMarkedCancelled() {
        ticket.getTravellers().set(2, t("M3", "Mira").toBuilder().cancelled(true).build());
        ticket.getTravellers().add(t("M4", "Dev"));

        service.cancelPassengers("B1", request("M1"));

        ArgumentCaptor<BookingDocumentCreateRequest> edit = ArgumentCaptor.forClass(BookingDocumentCreateRequest.class);
        verify(documentService).updateDocument(eq("B1"), eq("D1"), edit.capture());
        // cost 27000 - (9000 - 3200) = 21200; price 30000 - (10000 - 3700) = 23700
        assertThat(edit.getValue().getNetCost()).isEqualByComparingTo("21200");
        assertThat(edit.getValue().getMarkupMode()).isEqualTo(MarkupMode.FLAT);
        assertThat(edit.getValue().getMarkupValue()).isEqualByComparingTo("2500");
        assertThat(edit.getValue().getPurchaseCurrency()).isNull();
        assertThat(edit.getValue().getReferenceNumber()).isEqualTo("PNR1");
        assertThat(edit.getValue().getTravellers()).extracting(VoucherTraveller::getMemberId).containsExactly("M1", "M2", "M3", "M4");
        assertThat(ticket.getTravellers()).extracting(VoucherTraveller::getCancelled).containsExactly(true, null, true, null);
        assertThat(booking.getPartlyCancelledAt()).isNotNull();
        // what was kept stays owed: a later full cancel adds it to its own charges
        assertThat(booking.getPartialSupplierCharge()).isEqualByComparingTo("3200");
        assertThat(booking.getPartialAgencyFee()).isEqualByComparingTo("500");
        verify(activityService).record("B1", "PASSENGERS_CANCELLED", "Cancelled Asha on ticket PNR1: share ₹10,000.00, kept ₹3,700.00 (Unwell)");
    }

    @Test
    void aTicketBoughtAbroadStaysInItsCurrencyAndNoReasonOrNamesIsFine() {
        ticket.setPurchaseCurrency("AED");
        ticket.setPurchaseRate(d("26.5"));
        ticket.setReferenceNumber(null);
        ticket.getTravellers().set(1, t("M2", null));
        PassengerCancelRequest r = request("M2");
        r.setReason(" ");
        r.setAirlineCharge(null);
        r.setSupplierCharge(null);
        r.setAgencyFee(null);

        service.cancelPassengers("B1", r);

        ArgumentCaptor<BookingDocumentCreateRequest> edit = ArgumentCaptor.forClass(BookingDocumentCreateRequest.class);
        verify(documentService).updateDocument(eq("B1"), eq("D1"), edit.capture());
        assertThat(edit.getValue().getPurchaseCurrency()).isEqualTo("AED");
        assertThat(edit.getValue().getPurchasePrice()).isEqualByComparingTo("679.25"); // 18000 / 26.5
        assertThat(edit.getValue().getNetCost()).isNull();
        verify(activityService).record("B1", "PASSENGERS_CANCELLED", "Cancelled  on ticket : share ₹10,000.00, kept ₹0.00");

        PassengerCancelRequest noReason = request("M1");
        noReason.setReason(null);
        ticket.setPurchaseRate(BigDecimal.ZERO);
        service.cancelPassengers("B1", noReason);
        verify(documentService, org.mockito.Mockito.times(2)).updateDocument(eq("B1"), eq("D1"), any());
    }

    @Test
    void severalTravellersAreNamedTogether() {
        service.cancelPassengers("B1", request("M1", "M2"));
        verify(activityService).record("B1", "PASSENGERS_CANCELLED", "Cancelled Asha, Ravi on ticket PNR1: share ₹10,000.00, kept ₹3,700.00 (Unwell)");
    }

    @Test
    void aTicketWithAForeignCurrencyButNoRateIsPricedInBase() {
        ticket.setPurchaseCurrency("AED");
        service.cancelPassengers("B1", request("M1"));
        ArgumentCaptor<BookingDocumentCreateRequest> edit = ArgumentCaptor.forClass(BookingDocumentCreateRequest.class);
        verify(documentService).updateDocument(eq("B1"), eq("D1"), edit.capture());
        assertThat(edit.getValue().getNetCost()).isEqualByComparingTo("21200");
    }

    @Test
    void onlyALiveUninvoicedFlightYouLoggedCanHaveSomePassengersCancelled() {
        booking.setType(BookingType.HOTEL);
        assertThatThrownBy(() -> service.cancelPassengers("B1", request("M1"))).isInstanceOf(BookingConflictException.class)
                .hasMessageStartingWith("Only a flight you logged");
        booking.setType(BookingType.FLIGHT);
        booking.setPlatformBookingId("PB1");
        assertThatThrownBy(() -> service.cancelPassengers("B1", request("M1"))).hasMessageStartingWith("Only a flight you logged");
        booking.setPlatformBookingId(null);
        booking.setBookingStatus(BookingStatus.PENDING);
        assertThatThrownBy(() -> service.cancelPassengers("B1", request("M1"))).hasMessage("Only a confirmed booking can have some passengers cancelled");
        booking.setBookingStatus(BookingStatus.COMPLETED);
        given(bookingService.invoiced("B1")).willReturn(new InvoiceLineSource.Invoiced(d("100"), Set.of(), null));
        assertThatThrownBy(() -> service.cancelPassengers("B1", request("M1")))
                .hasMessage("This booking is invoiced - cancel its passengers with a credit note in Accounts");
        verify(documentService, never()).updateDocument(anyString(), anyString(), any());
    }

    @Test
    void thePickMustBeSomeoneStillFlyingOnOneTicketAndNotEveryone() {
        assertThatThrownBy(() -> service.cancelPassengers("B1", request("M9")))
                .hasMessage("Pick travellers still flying on one ticket of this booking");
        assertThatThrownBy(() -> service.cancelPassengers("B1", request("M1", "M2", "M3")))
                .hasMessage("That is everyone on the ticket - cancel the whole booking instead");
        ticket.getTravellers().set(0, t("M1", "Asha").toBuilder().cancelled(true).build());
        assertThatThrownBy(() -> service.cancelPassengers("B1", request("M1")))
                .hasMessage("Pick travellers still flying on one ticket of this booking");
        verify(documentService, never()).updateDocument(anyString(), anyString(), any());
    }

    @Test
    void theRestOfTheTicketCannotBeSoldBelowItsCost() {
        PassengerCancelRequest r = request("M1");
        r.setSellingShare(d("15000"));
        r.setPurchaseShare(d("5000"));
        r.setAirlineCharge(null);
        r.setSupplierCharge(null);
        r.setAgencyFee(null);
        assertThatThrownBy(() -> service.cancelPassengers("B1", r))
                .hasMessage("Those shares would sell the rest of the ticket below its cost - check the selling and purchase shares");
        verify(documentService, never()).updateDocument(anyString(), anyString(), any());
    }

    @Test
    void earlierPartialChargesAddUp() {
        booking.setPartialSupplierCharge(d("1000"));
        booking.setPartialAgencyFee(null);
        service.cancelPassengers("B1", request("M1"));
        assertThat(booking.getPartialSupplierCharge()).isEqualByComparingTo("4200");
        assertThat(booking.getPartialAgencyFee()).isEqualByComparingTo("500");
    }

    @Test
    void theShareAndTheChargesStayWithinTheTicket() {
        PassengerCancelRequest tooMuch = request("M1");
        tooMuch.setPurchaseShare(d("27001"));
        assertThatThrownBy(() -> service.cancelPassengers("B1", tooMuch)).hasMessage("The share can't be more than the ticket's price");
        PassengerCancelRequest tooDear = request("M1");
        tooDear.setSellingShare(d("30001"));
        assertThatThrownBy(() -> service.cancelPassengers("B1", tooDear)).hasMessage("The share can't be more than the ticket's price");
        PassengerCancelRequest supplierKeepsMore = request("M1");
        supplierKeepsMore.setAirlineCharge(d("9000"));
        assertThatThrownBy(() -> service.cancelPassengers("B1", supplierKeepsMore)).hasMessage("The charges can't be more than the cancelled share");
        PassengerCancelRequest agencyKeepsMore = request("M1");
        agencyKeepsMore.setAgencyFee(d("7000"));
        assertThatThrownBy(() -> service.cancelPassengers("B1", agencyKeepsMore)).hasMessage("The charges can't be more than the cancelled share");
        ticket.setNetCost(null);
        ticket.setSellingPrice(null);
        assertThatThrownBy(() -> service.cancelPassengers("B1", request("M1"))).hasMessage("The share can't be more than the ticket's price");
    }
}
