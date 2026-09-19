package com.voyra.crm.service;

import com.voyra.crm.dto.CreditNoteRefundRequest;
import com.voyra.crm.dto.CreditNoteRequest;
import com.voyra.crm.dto.CreditNoteResponse;
import com.voyra.crm.entity.CreditNote;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.entity.PaymentReceipt;
import com.voyra.crm.enums.CreditNoteReason;
import com.voyra.crm.enums.CreditNoteStatus;
import com.voyra.crm.enums.DocumentKind;
import com.voyra.crm.enums.FxRateSource;
import com.voyra.crm.enums.InvoiceDocumentType;
import com.voyra.crm.enums.InvoiceLifecycle;
import com.voyra.crm.enums.PaymentMode;
import com.voyra.crm.enums.ReceiptDirection;
import com.voyra.crm.enums.SupplyNature;
import com.voyra.crm.enums.TaxTreatment;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.CreditNoteRepository;
import com.voyra.crm.repository.InvoiceRepository;
import com.voyra.crm.repository.PaymentReceiptRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
class CreditNoteServiceTest {

    @Mock
    private CreditNoteRepository creditNoteRepository;
    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private PaymentReceiptRepository paymentReceiptRepository;
    @Mock
    private com.voyra.crm.repository.BookingRepository bookingRepository;
    @Mock
    private DocumentNumberService documentNumberService;
    @Mock
    private AuditService auditService;
    @Mock
    private CustomerLedgerService customerLedgerService;
    @Mock
    private BookingAccountingSync bookingAccountingSync;

    @InjectMocks
    private CreditNoteService creditNoteService;

    @BeforeEach
    void authenticateAsAccountant() {
        CustomUserPrincipal principal = new CustomUserPrincipal("AC1", "neha", UserType.ACCOUNTANT, "T1");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** taxableValue 1000, cgst 90, sgst 90 (18% intra-state), grandTotal 1180. */
    private Invoice issuedInvoice() {
        return Invoice.builder().id("I1").invoiceNumber("INV/2026-27/0001")
                .documentType(InvoiceDocumentType.TAX_INVOICE).status(InvoiceLifecycle.ISSUED)
                .clientId("K1").clientName("Rahul Verma").bookingId("B1")
                .supplyNature(SupplyNature.DOMESTIC_PACKAGE).taxTreatment(TaxTreatment.INTRA_STATE)
                .placeOfSupplyCode("27").currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .fxRateSource(FxRateSource.INR_IDENTITY)
                .taxableValue(new BigDecimal("1000.00")).cgstAmount(new BigDecimal("90.00"))
                .sgstAmount(new BigDecimal("90.00")).igstAmount(BigDecimal.ZERO).tcsAmount(BigDecimal.ZERO)
                .grandTotal(new BigDecimal("1180.00")).grandTotalInr(new BigDecimal("1180.00"))
                .amountReceived(BigDecimal.ZERO).creditNoteTotal(BigDecimal.ZERO)
                .balanceDue(new BigDecimal("1180.00")).balanceDueInr(new BigDecimal("1180.00")).build();
    }

    private CreditNoteRequest request(BigDecimal cancellationFee) {
        CreditNoteRequest r = new CreditNoteRequest();
        r.setInvoiceId("I1");
        r.setReason(CreditNoteReason.BOOKING_CANCELLED);
        r.setReasonNote("Client cancelled");
        r.setCancellationFee(cancellationFee);
        return r;
    }

    @Test
    void draftingAgainstADraftInvoiceIsRejected() {
        Invoice draft = Invoice.builder().id("I1").documentType(InvoiceDocumentType.TAX_INVOICE)
                .status(InvoiceLifecycle.DRAFT).build();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> creditNoteService.create(request(BigDecimal.ZERO)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only an issued invoice can be credited");
    }

    @Test
    void bookingCancelledReasonIsRejectedWhenTheBookingIsNotActuallyCancelled() {
        Invoice invoice = issuedInvoice();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(invoice));
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(
                com.voyra.crm.entity.Booking.builder().id("B1").bookingStatus(com.voyra.crm.enums.BookingStatus.CONFIRMED).build()));

        assertThatThrownBy(() -> creditNoteService.create(request(BigDecimal.ZERO)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires the booking to actually be cancelled");
    }

    @Test
    void billingErrorReasonWorksOnALiveBooking() {
        Invoice invoice = issuedInvoice();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(invoice));
        when(creditNoteRepository.existsById(any())).thenReturn(false);
        when(creditNoteRepository.findByInvoiceIdAndStatus("I1", CreditNoteStatus.ISSUED)).thenReturn(List.of());

        CreditNoteRequest request = request(BigDecimal.ZERO);
        request.setReason(CreditNoteReason.BILLING_ERROR);

        CreditNoteResponse response = creditNoteService.create(request);

        assertThat(response.getStatus()).isEqualTo(CreditNoteStatus.DRAFT);
        org.mockito.Mockito.verifyNoInteractions(bookingRepository);
    }

    @Test
    void aFullCreditReversesTheEntireTaxableValueAndTax() {
        Invoice invoice = issuedInvoice();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(invoice));
        when(creditNoteRepository.existsById(any())).thenReturn(false);
        when(creditNoteRepository.findByInvoiceIdAndStatus("I1", CreditNoteStatus.ISSUED)).thenReturn(List.of());

        CreditNoteResponse response = creditNoteService.create(request(BigDecimal.ZERO));

        assertThat(response.getStatus()).isEqualTo(CreditNoteStatus.DRAFT);
        assertThat(response.getTaxableValue()).isEqualByComparingTo("1000.00");
        assertThat(response.getCgstAmount()).isEqualByComparingTo("90.00");
        assertThat(response.getSgstAmount()).isEqualByComparingTo("90.00");
        assertThat(response.getTotalAmount()).isEqualByComparingTo("1180.00");
    }

    @Test
    void aPartialCreditWithARetentionFeeReversesOnlyItsProRataShare() {
        Invoice invoice = issuedInvoice();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(invoice));
        when(creditNoteRepository.existsById(any())).thenReturn(false);
        when(creditNoteRepository.findByInvoiceIdAndStatus("I1", CreditNoteStatus.ISSUED)).thenReturn(List.of());

        // Retain 200 of the 1000 taxable value -> 80% credited.
        CreditNoteResponse response = creditNoteService.create(request(new BigDecimal("200.00")));

        assertThat(response.getTaxableValue()).isEqualByComparingTo("800.00");
        assertThat(response.getCgstAmount()).isEqualByComparingTo("72.00");
        assertThat(response.getSgstAmount()).isEqualByComparingTo("72.00");
        assertThat(response.getTotalAmount()).isEqualByComparingTo("944.00");
    }

    @Test
    void cancellationFeeAboveTaxableValueIsRejected() {
        Invoice invoice = issuedInvoice();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> creditNoteService.create(request(new BigDecimal("5000.00"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot exceed");
    }

    @Test
    void issuingAllocatesANumberAndReducesTheInvoiceBalance() {
        Invoice invoice = issuedInvoice();
        CreditNote draft = CreditNote.builder().id("CN1").invoiceId("I1").clientId("K1")
                .status(CreditNoteStatus.DRAFT).currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .taxableValue(new BigDecimal("1000.00")).cgstAmount(new BigDecimal("90.00"))
                .sgstAmount(new BigDecimal("90.00")).totalAmount(new BigDecimal("1180.00"))
                .totalAmountInr(new BigDecimal("1180.00")).refundedAmount(BigDecimal.ZERO).build();
        when(creditNoteRepository.findById("CN1")).thenReturn(Optional.of(draft));
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(invoice));
        when(creditNoteRepository.findByInvoiceIdAndStatus("I1", CreditNoteStatus.ISSUED)).thenReturn(List.of());
        when(documentNumberService.next(DocumentKind.CREDIT_NOTE, LocalDate.now())).thenReturn("CN/2026-27/0001");

        CreditNoteResponse response = creditNoteService.issue("CN1");

        assertThat(response.getCreditNoteNumber()).isEqualTo("CN/2026-27/0001");
        assertThat(response.getStatus()).isEqualTo(CreditNoteStatus.ISSUED);
        assertThat(response.getRefundableAmount()).isEqualByComparingTo("1180.00");
        assertThat(invoice.getCreditNoteTotal()).isEqualByComparingTo("1180.00");
        assertThat(invoice.getBalanceDue()).isEqualByComparingTo("0.00");
        assertThat(invoice.getStatus()).isEqualTo(InvoiceLifecycle.PAID);
    }

    @Test
    void issuingBeyondTheInvoicesGrandTotalIsRejected() {
        Invoice invoice = issuedInvoice();
        CreditNote draft = CreditNote.builder().id("CN1").invoiceId("I1").clientId("K1")
                .status(CreditNoteStatus.DRAFT).totalAmount(new BigDecimal("1180.00")).build();
        CreditNote alreadyIssued = CreditNote.builder().id("CN0").invoiceId("I1")
                .status(CreditNoteStatus.ISSUED).totalAmount(new BigDecimal("500.00")).build();
        when(creditNoteRepository.findById("CN1")).thenReturn(Optional.of(draft));
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(invoice));
        when(creditNoteRepository.findByInvoiceIdAndStatus("I1", CreditNoteStatus.ISSUED)).thenReturn(List.of(alreadyIssued));

        assertThatThrownBy(() -> creditNoteService.issue("CN1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("more than the invoice's grand total");
    }

    @Test
    void cancellingAnIssuedCreditNoteWithNoRefundsReversesTheLedgerAndInvoiceBalance() {
        Invoice invoice = issuedInvoice();
        invoice.setCreditNoteTotal(new BigDecimal("1180.00"));
        invoice.setBalanceDue(BigDecimal.ZERO);
        invoice.setBalanceDueInr(BigDecimal.ZERO);
        invoice.setStatus(InvoiceLifecycle.PAID);
        CreditNote issued = CreditNote.builder().id("CN1").invoiceId("I1").clientId("K1")
                .creditNoteNumber("CN/2026-27/0001").status(CreditNoteStatus.ISSUED)
                .currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .totalAmount(new BigDecimal("1180.00")).totalAmountInr(new BigDecimal("1180.00"))
                .refundedAmount(BigDecimal.ZERO).build();
        when(creditNoteRepository.findById("CN1")).thenReturn(Optional.of(issued));
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(invoice));

        CreditNoteResponse response = creditNoteService.cancel("CN1", "Raised in error");

        assertThat(response.getStatus()).isEqualTo(CreditNoteStatus.CANCELLED);
        assertThat(invoice.getCreditNoteTotal()).isEqualByComparingTo("0.00");
        assertThat(invoice.getBalanceDue()).isEqualByComparingTo("1180.00");
        assertThat(invoice.getStatus()).isEqualTo(InvoiceLifecycle.ISSUED);
    }

    @Test
    void cancellingACreditNoteWithRefundsAgainstItIsRejected() {
        CreditNote issued = CreditNote.builder().id("CN1").invoiceId("I1").status(CreditNoteStatus.ISSUED)
                .totalAmount(new BigDecimal("1180.00")).refundedAmount(new BigDecimal("500.00")).build();
        when(creditNoteRepository.findById("CN1")).thenReturn(Optional.of(issued));

        assertThatThrownBy(() -> creditNoteService.cancel("CN1", "oops"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refunds against it");
    }

    @Test
    void refundingWritesAReceiptAndAccumulatesRefundedAmount() {
        CreditNote issued = CreditNote.builder().id("CN1").invoiceId("I1").clientId("K1")
                .creditNoteNumber("CN/2026-27/0001").status(CreditNoteStatus.ISSUED)
                .currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .totalAmount(new BigDecimal("1180.00")).refundableAmount(new BigDecimal("1180.00"))
                .refundedAmount(BigDecimal.ZERO).build();
        when(creditNoteRepository.findById("CN1")).thenReturn(Optional.of(issued));
        when(paymentReceiptRepository.findByCreditNoteIdOrderByReceivedOnAscCreatedAtAsc("CN1")).thenReturn(List.of());
        when(paymentReceiptRepository.existsById(any())).thenReturn(false);
        when(documentNumberService.next(DocumentKind.RECEIPT, LocalDate.of(2026, 9, 19))).thenReturn("RCP/2026-27/0010");

        CreditNoteRefundRequest request = new CreditNoteRefundRequest();
        request.setAmount(new BigDecimal("500.00"));
        request.setPaymentMode(PaymentMode.BANK_TRANSFER);
        request.setReceivedOn(LocalDate.of(2026, 9, 19));

        CreditNoteResponse response = creditNoteService.refund("CN1", request);

        assertThat(response.getRefundedAmount()).isEqualByComparingTo("500.00");
        org.mockito.ArgumentCaptor<PaymentReceipt> captor = org.mockito.ArgumentCaptor.forClass(PaymentReceipt.class);
        org.mockito.Mockito.verify(paymentReceiptRepository).save(captor.capture());
        assertThat(captor.getValue().getDirection()).isEqualTo(ReceiptDirection.REFUND);
        assertThat(captor.getValue().getCreditNoteId()).isEqualTo("CN1");
        assertThat(captor.getValue().getAmount()).isEqualByComparingTo("500.00");
    }

    @Test
    void refundingMoreThanTheRemainingBalanceIsRejected() {
        CreditNote issued = CreditNote.builder().id("CN1").invoiceId("I1").clientId("K1")
                .status(CreditNoteStatus.ISSUED).refundableAmount(new BigDecimal("1180.00"))
                .refundedAmount(new BigDecimal("1000.00")).build();
        when(creditNoteRepository.findById("CN1")).thenReturn(Optional.of(issued));
        when(paymentReceiptRepository.findByCreditNoteIdOrderByReceivedOnAscCreatedAtAsc("CN1"))
                .thenReturn(List.of(PaymentReceipt.builder().amount(new BigDecimal("1000.00")).build()));

        CreditNoteRefundRequest request = new CreditNoteRefundRequest();
        request.setAmount(new BigDecimal("500.00"));
        request.setPaymentMode(PaymentMode.CASH);
        request.setReceivedOn(LocalDate.of(2026, 9, 19));

        assertThatThrownBy(() -> creditNoteService.refund("CN1", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds the remaining refundable balance");
    }
}
