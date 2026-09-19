package com.voyra.crm.service;

import com.voyra.crm.dto.PaymentReceiptRequest;
import com.voyra.crm.dto.PaymentReceiptResponse;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.entity.PaymentReceipt;
import com.voyra.crm.enums.DocumentKind;
import com.voyra.crm.enums.FxRateSource;
import com.voyra.crm.enums.InvoiceDocumentType;
import com.voyra.crm.enums.InvoiceLifecycle;
import com.voyra.crm.enums.PaymentMode;
import com.voyra.crm.enums.ReceiptDirection;
import com.voyra.crm.enums.SupplyNature;
import com.voyra.crm.enums.TaxTreatment;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.InvoiceRepository;
import com.voyra.crm.repository.PaymentReceiptRepository;
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
class PaymentReceiptServiceTest {

    @Mock
    private PaymentReceiptRepository paymentReceiptRepository;
    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private DocumentNumberService documentNumberService;
    @Mock
    private AuditService auditService;
    @Mock
    private CustomerLedgerService customerLedgerService;
    @Mock
    private BookingAccountingSync bookingAccountingSync;

    @InjectMocks
    private PaymentReceiptService paymentReceiptService;

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

    private Invoice issuedInvoice(BigDecimal grandTotal) {
        return Invoice.builder().id("I1").documentType(InvoiceDocumentType.TAX_INVOICE)
                .status(InvoiceLifecycle.ISSUED).clientId("K1").bookingId("B1")
                .supplyNature(SupplyNature.DOMESTIC_PACKAGE).taxTreatment(TaxTreatment.INTRA_STATE)
                .placeOfSupplyCode("27").currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .fxRateSource(FxRateSource.INR_IDENTITY).grandTotal(grandTotal).grandTotalInr(grandTotal)
                .amountReceived(BigDecimal.ZERO).creditNoteTotal(BigDecimal.ZERO)
                .balanceDue(grandTotal).balanceDueInr(grandTotal).build();
    }

    private PaymentReceiptRequest request(BigDecimal amount) {
        PaymentReceiptRequest r = new PaymentReceiptRequest();
        r.setInvoiceId("I1");
        r.setAmount(amount);
        r.setPaymentMode(PaymentMode.BANK_TRANSFER);
        r.setReceivedOn(LocalDate.of(2026, 9, 19));
        return r;
    }

    @Test
    void recordingAgainstADraftIsRejected() {
        Invoice draft = Invoice.builder().id("I1").status(InvoiceLifecycle.DRAFT).build();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> paymentReceiptService.record(request(new BigDecimal("100"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aPartialReceiptMovesTheInvoiceToPartiallyPaid() {
        Invoice invoice = issuedInvoice(new BigDecimal("1000.00"));
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(invoice));
        when(paymentReceiptRepository.existsById(any())).thenReturn(false);
        when(documentNumberService.next(DocumentKind.RECEIPT, LocalDate.of(2026, 9, 19))).thenReturn("RCP/2026-27/0001");
        when(paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc("I1"))
                .thenReturn(List.of(PaymentReceipt.builder().id("R1").invoiceId("I1")
                        .direction(ReceiptDirection.RECEIPT).amount(new BigDecimal("400.00")).build()));

        PaymentReceiptResponse response = paymentReceiptService.record(request(new BigDecimal("400.00")));

        assertThat(response.getReceiptNumber()).isEqualTo("RCP/2026-27/0001");
        assertThat(response.getAmountInr()).isEqualByComparingTo("400.00");
        assertThat(response.getIsAdvance()).isFalse();
        assertThat(invoice.getStatus()).isEqualTo(InvoiceLifecycle.PARTIALLY_PAID);
        assertThat(invoice.getAmountReceived()).isEqualByComparingTo("400.00");
        assertThat(invoice.getBalanceDue()).isEqualByComparingTo("600.00");
    }

    @Test
    void aReceiptThatClearsTheBalanceMovesTheInvoiceToPaid() {
        Invoice invoice = issuedInvoice(new BigDecimal("1000.00"));
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(invoice));
        when(paymentReceiptRepository.existsById(any())).thenReturn(false);
        when(documentNumberService.next(DocumentKind.RECEIPT, LocalDate.of(2026, 9, 19))).thenReturn("RCP/2026-27/0001");
        when(paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc("I1"))
                .thenReturn(List.of(PaymentReceipt.builder().id("R1").invoiceId("I1")
                        .direction(ReceiptDirection.RECEIPT).amount(new BigDecimal("1000.00")).build()));

        paymentReceiptService.record(request(new BigDecimal("1000.00")));

        assertThat(invoice.getStatus()).isEqualTo(InvoiceLifecycle.PAID);
        assertThat(invoice.getBalanceDue()).isEqualByComparingTo("0.00");
    }

    @Test
    void anAdvanceReceiptAgainstAProformaNeverChangesItsStatus() {
        Invoice proforma = Invoice.builder().id("I1").documentType(InvoiceDocumentType.PROFORMA)
                .status(InvoiceLifecycle.PROFORMA_ISSUED).clientId("K1").currencyCode("INR")
                .fxRateToInr(BigDecimal.ONE).grandTotal(new BigDecimal("1000.00")).build();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(proforma));
        when(paymentReceiptRepository.existsById(any())).thenReturn(false);
        when(documentNumberService.next(DocumentKind.RECEIPT, LocalDate.of(2026, 9, 19))).thenReturn("RCP/2026-27/0001");

        PaymentReceiptResponse response = paymentReceiptService.record(request(new BigDecimal("300.00")));

        assertThat(response.getIsAdvance()).isTrue();
        assertThat(proforma.getStatus()).isEqualTo(InvoiceLifecycle.PROFORMA_ISSUED);
        org.mockito.Mockito.verify(invoiceRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void reversingAnAlreadyReversedReceiptIsRejected() {
        PaymentReceipt already = PaymentReceipt.builder().id("R1").invoiceId("I1")
                .reversedAt(java.time.LocalDateTime.now()).build();
        when(paymentReceiptRepository.findById("R1")).thenReturn(Optional.of(already));

        assertThatThrownBy(() -> paymentReceiptService.reverse("R1", "oops"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already been reversed");
    }

    @Test
    void reversingAReversalIsRejected() {
        PaymentReceipt reversal = PaymentReceipt.builder().id("R2").invoiceId("I1").reversesReceiptId("R1").build();
        when(paymentReceiptRepository.findById("R2")).thenReturn(Optional.of(reversal));

        assertThatThrownBy(() -> paymentReceiptService.reverse("R2", "oops"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot reverse a reversing entry");
    }

    @Test
    void reversingARecordedReceiptWritesAnOppositeSignedRowAndRecomputesTheInvoice() {
        Invoice invoice = issuedInvoice(new BigDecimal("1000.00"));
        invoice.setAmountReceived(new BigDecimal("400.00"));
        invoice.setBalanceDue(new BigDecimal("600.00"));
        invoice.setStatus(InvoiceLifecycle.PARTIALLY_PAID);
        PaymentReceipt original = PaymentReceipt.builder().id("R1").invoiceId("I1").clientId("K1")
                .direction(ReceiptDirection.RECEIPT).currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .amount(new BigDecimal("400.00")).amountInr(new BigDecimal("400.00"))
                .paymentMode(PaymentMode.UPI).isAdvance(false).build();
        when(paymentReceiptRepository.findById("R1")).thenReturn(Optional.of(original));
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(invoice));
        when(paymentReceiptRepository.existsById(any())).thenReturn(false);
        when(documentNumberService.next(DocumentKind.RECEIPT, LocalDate.now())).thenReturn("RCP/2026-27/0002");
        when(paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc("I1"))
                .thenReturn(List.of()); // net zero after reversal

        PaymentReceiptResponse reversal = paymentReceiptService.reverse("R1", "wrong invoice");

        assertThat(reversal.getAmount()).isEqualByComparingTo("-400.00");
        assertThat(reversal.getReversesReceiptId()).isEqualTo("R1");
        assertThat(original.getReversedAt()).isNotNull();
        assertThat(original.getReversalReason()).isEqualTo("wrong invoice");
        assertThat(invoice.getStatus()).isEqualTo(InvoiceLifecycle.ISSUED);
        assertThat(invoice.getAmountReceived()).isEqualByComparingTo("0.00");
    }

    @Test
    void recordAuditsAsAPaymentReceiptCreate() {
        Invoice invoice = issuedInvoice(new BigDecimal("1000.00"));
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(invoice));
        when(paymentReceiptRepository.existsById(any())).thenReturn(false);
        when(documentNumberService.next(any(), any())).thenReturn("RCP/2026-27/0001");
        when(paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc("I1")).thenReturn(List.of());

        paymentReceiptService.record(request(new BigDecimal("100.00")));

        ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(auditService).recordCreate(
                org.mockito.ArgumentMatchers.eq(com.voyra.crm.enums.AuditEntityType.PAYMENT_RECEIPT),
                idCaptor.capture(), org.mockito.ArgumentMatchers.eq("RCP/2026-27/0001"));
    }
}
