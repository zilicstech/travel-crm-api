package com.voyra.crm.service;

import com.voyra.crm.dto.InvoiceDraftRequest;
import com.voyra.crm.dto.InvoiceLineItemRequest;
import com.voyra.crm.dto.InvoiceResponse;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.Client;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.entity.InvoiceLineItem;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.DocumentKind;
import com.voyra.crm.enums.FxRateSource;
import com.voyra.crm.enums.InvoiceDocumentType;
import com.voyra.crm.enums.InvoiceLifecycle;
import com.voyra.crm.enums.PaymentStatus;
import com.voyra.crm.enums.SupplyNature;
import com.voyra.crm.enums.TaxTreatment;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.models.TaxComputationResult;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.InvoiceLineItemRepository;
import com.voyra.crm.repository.InvoiceRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvoiceDocumentServiceTest {

    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private InvoiceLineItemRepository invoiceLineItemRepository;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private ClientService clientService;
    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private TaxEngine taxEngine;
    @Mock
    private DocumentNumberService documentNumberService;
    @Mock
    private AuditService auditService;

    @InjectMocks
    private InvoiceDocumentService invoiceDocumentService;

    @BeforeEach
    void authenticateAsAccountant() {
        CustomUserPrincipal principal = new CustomUserPrincipal("AC1", "neha", UserType.ACCOUNTANT, "T1");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        org.mockito.Mockito.lenient().when(tenantRepository.findById("T1")).thenReturn(Optional.of(
                Tenant.builder().id("T1").agencyName("Global Explorer").legalName("Global Explorer Pvt Ltd")
                        .gstNumber("27AAAAA0000A1Z5").stateCode("27").address("Mumbai").build()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private Booking booking() {
        return Booking.builder().id("B1").clientId("K1").clientName("Arjun Mehta").agentId("A1").agentName("Liam")
                .type(BookingType.PACKAGE).destination("Dubai").bookingStatus(BookingStatus.CONFIRMED)
                .paymentStatus(PaymentStatus.PENDING).build();
    }

    private Client client() {
        return Client.builder().id("K1").name("Arjun Mehta").stateCode("27").build();
    }

    private InvoiceLineItemRequest lineRequest() {
        InvoiceLineItemRequest line = new InvoiceLineItemRequest();
        line.setDescription("Dubai package");
        line.setQuantity(BigDecimal.ONE);
        line.setUnitPrice(new BigDecimal("50000.00"));
        return line;
    }

    private TaxComputationResult gstResult(BigDecimal taxableAmount) {
        BigDecimal cgst = taxableAmount.multiply(new BigDecimal("0.025"));
        return new TaxComputationResult(
                TaxTreatment.INTRA_STATE, "27", taxableAmount,
                new BigDecimal("5.000"), new BigDecimal("2.500"), new BigDecimal("2.500"), BigDecimal.ZERO,
                cgst, cgst, BigDecimal.ZERO, cgst.add(cgst),
                BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO,
                taxableAmount.add(cgst).add(cgst));
    }

    @Test
    void createDraftRejectsACancelledBooking() {
        Booking cancelled = booking().toBuilder().bookingStatus(BookingStatus.CANCELLED).build();
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(cancelled));

        InvoiceDraftRequest request = new InvoiceDraftRequest();
        request.setBookingId("B1");
        request.setSupplyNature(SupplyNature.DOMESTIC_PACKAGE);

        assertThatThrownBy(() -> invoiceDocumentService.createDraft(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cancelled booking");
    }

    @Test
    void createDraftRejectsASecondInvoiceOnTheSameBooking() {
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking()));
        when(invoiceRepository.existsByBookingIdAndDocumentTypeAndStatusNot(
                "B1", InvoiceDocumentType.TAX_INVOICE, InvoiceLifecycle.CANCELLED)).thenReturn(true);

        InvoiceDraftRequest request = new InvoiceDraftRequest();
        request.setBookingId("B1");
        request.setSupplyNature(SupplyNature.DOMESTIC_PACKAGE);

        assertThatThrownBy(() -> invoiceDocumentService.createDraft(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already has an invoice");
    }

    @Test
    void nonInrCurrencyRequiresAPositiveFxRate() {
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking()));
        when(invoiceRepository.existsByBookingIdAndDocumentTypeAndStatusNot(any(), any(), any())).thenReturn(false);
        when(clientService.findAccessibleClient("K1")).thenReturn(client());

        InvoiceDraftRequest request = new InvoiceDraftRequest();
        request.setBookingId("B1");
        request.setSupplyNature(SupplyNature.DOMESTIC_PACKAGE);
        request.setCurrencyCode("USD");

        assertThatThrownBy(() -> invoiceDocumentService.createDraft(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fxRateToInr");
    }

    @Test
    void createDraftComputesTotalsFromEachLineAndLocksNothingYet() {
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking()));
        when(invoiceRepository.existsByBookingIdAndDocumentTypeAndStatusNot(any(), any(), any())).thenReturn(false);
        when(clientService.findAccessibleClient("K1")).thenReturn(client());
        when(invoiceRepository.existsById(any())).thenReturn(false);
        when(invoiceLineItemRepository.existsById(any())).thenReturn(false);
        when(taxEngine.compute(any())).thenReturn(gstResult(new BigDecimal("50000.00")));

        InvoiceDraftRequest request = new InvoiceDraftRequest();
        request.setBookingId("B1");
        request.setSupplyNature(SupplyNature.DOMESTIC_PACKAGE);
        request.setLines(List.of(lineRequest()));

        InvoiceResponse response = invoiceDocumentService.createDraft(request);

        assertThat(response.getStatus()).isEqualTo(InvoiceLifecycle.DRAFT);
        assertThat(response.getInvoiceNumber()).isNull();
        assertThat(response.getGrandTotal()).isEqualByComparingTo("52500.00");
        assertThat(response.getGrandTotalInr()).isEqualByComparingTo("52500.00");
        assertThat(response.getFxRateToInr()).isEqualByComparingTo("1");
        assertThat(response.getFxRateSource()).isEqualTo(FxRateSource.INR_IDENTITY);
    }

    @Test
    void issuingAUsdInvoiceStoresGrandTotalInrAtTheRateStampedAtIssue() {
        Invoice draft = Invoice.builder()
                .id("I1").documentType(InvoiceDocumentType.TAX_INVOICE).status(InvoiceLifecycle.DRAFT)
                .clientId("K1").clientName("Arjun Mehta").agentId("A1")
                .supplyNature(SupplyNature.DOMESTIC_PACKAGE).taxTreatment(TaxTreatment.INTRA_STATE)
                .placeOfSupplyCode("27").currencyCode("USD").fxRateToInr(new BigDecimal("83.120000"))
                .grandTotal(new BigDecimal("1000.00")).grandTotalInr(new BigDecimal("83120.00"))
                .build();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(draft));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1"))
                .thenReturn(List.of(InvoiceLineItem.builder().id("L1").invoiceId("I1").description("x").build()));
        when(documentNumberService.next(DocumentKind.TAX_INVOICE, LocalDate.now())).thenReturn("INV/2026-27/0001");

        InvoiceResponse response = invoiceDocumentService.issue("I1");

        assertThat(response.getStatus()).isEqualTo(InvoiceLifecycle.ISSUED);
        assertThat(response.getInvoiceNumber()).isEqualTo("INV/2026-27/0001");
        assertThat(response.getFxLockedAt()).isNotNull();
        assertThat(response.getGrandTotalInr()).isEqualByComparingTo("83120.00");
    }

    @Test
    void issuingWithNoLinesIsRejected() {
        Invoice draft = Invoice.builder().id("I1").status(InvoiceLifecycle.DRAFT).build();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(draft));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of());

        assertThatThrownBy(() -> invoiceDocumentService.issue("I1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("At least one line");
    }

    @Test
    void anIssuedInvoiceRejectsEveryEdit() {
        Invoice issued = Invoice.builder().id("I1").status(InvoiceLifecycle.ISSUED).build();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(issued));

        InvoiceDraftRequest request = new InvoiceDraftRequest();
        request.setSupplyNature(SupplyNature.DOMESTIC_PACKAGE);

        assertThatThrownBy(() -> invoiceDocumentService.updateDraft("I1", request))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void deletingADraftNeverTouchesTheNumberSequence() {
        Invoice draft = Invoice.builder().id("I1").status(InvoiceLifecycle.DRAFT).build();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(draft));

        invoiceDocumentService.deleteDraft("I1");

        org.mockito.Mockito.verifyNoInteractions(documentNumberService);
        org.mockito.Mockito.verify(invoiceRepository).delete(draft);
    }

    @Test
    void cancellingRequiresAnIssuedInvoiceWithNoReceipts() {
        Invoice issuedWithReceipts = Invoice.builder().id("I1").status(InvoiceLifecycle.ISSUED)
                .amountReceived(new BigDecimal("500.00")).build();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(issuedWithReceipts));

        assertThatThrownBy(() -> invoiceDocumentService.cancel("I1", "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("credit note");
    }

    @Test
    void cancellingAnIssuedInvoiceWithNoReceiptsSucceeds() {
        Invoice issued = Invoice.builder().id("I1").status(InvoiceLifecycle.ISSUED)
                .amountReceived(BigDecimal.ZERO).build();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(issued));

        InvoiceResponse response = invoiceDocumentService.cancel("I1", "Raised in error");

        assertThat(response.getStatus()).isEqualTo(InvoiceLifecycle.CANCELLED);
        assertThat(response.getCancelReason()).isEqualTo("Raised in error");
    }
}
