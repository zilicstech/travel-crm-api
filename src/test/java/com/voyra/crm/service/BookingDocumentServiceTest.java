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
    @InjectMocks private BookingDocumentService service;

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

    @Test
    void anEditCannotTakeThePriceBelowWhatIsInvoiced() {
        stub();
        when(bookingService.invoicedPrice("B1")).thenReturn(new BigDecimal("4400"));
        booking.setSellingPrice(new BigDecimal("3300"));

        assertThatThrownBy(() -> service.updateDocument("B1", "D1", edit("3000", "10"))).hasMessageContaining("already invoiced");
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
        r.setDetails(java.util.Map.of("airline", "IndiGo", "hotelName", "dropped"));

        service.updateDocument("B1", "D1", r);

        assertThat(voucher.getDetails()).containsExactly(java.util.Map.entry("airline", "IndiGo"));
    }
}
