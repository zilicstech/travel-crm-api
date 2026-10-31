package com.voyra.crm.service;

import com.voyra.crm.dto.InvoiceWalletResponse;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.dto.PaymentReceiptApplyAdvanceRequest;
import com.voyra.crm.dto.PaymentReceiptRequest;
import com.voyra.crm.dto.PaymentReceiptResponse;
import com.voyra.crm.dto.RefundSettlementRequest;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.Client;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.entity.JournalEntry;
import com.voyra.crm.entity.PaymentReceipt;
import com.voyra.crm.enums.AuditEntityType;
import com.voyra.crm.enums.DocumentKind;
import com.voyra.crm.enums.InvoiceLifecycle;
import com.voyra.crm.enums.JournalPurpose;
import com.voyra.crm.enums.JournalSourceType;
import com.voyra.crm.enums.LedgerEntryType;
import com.voyra.crm.enums.PaymentMode;
import com.voyra.crm.enums.ReceiptDirection;
import com.voyra.crm.enums.SystemAccount;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.models.JournalPosting;
import com.voyra.crm.models.LedgerPosting;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.CreditNoteRepository;
import com.voyra.crm.repository.InvoiceRepository;
import com.voyra.crm.repository.JournalEntryRepository;
import com.voyra.crm.repository.PaymentReceiptRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.util.FinancialYear;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Branch coverage for PaymentReceiptService, with its line source mocked. */
@ExtendWith(MockitoExtension.class)
class PaymentReceiptServiceCoverageTest {

    @Mock PaymentReceiptRepository paymentReceiptRepository;
    @Mock InvoiceRepository invoiceRepository;
    @Mock ClientService clientService;
    @Mock DocumentNumberService documentNumberService;
    @Mock AuditService auditService;
    @Mock CustomerLedgerService customerLedgerService;
    @Mock BookingAccountingSync bookingAccountingSync;
    @Mock BookingRepository bookingRepository;
    @Mock JournalService journalService;
    @Mock JournalEntryRepository journalEntryRepository;
    @Spy BaseCurrency baseCurrency = new BaseCurrency(null);
    @Mock InvoiceLineSource invoiceLineSource;
    @Mock CreditNoteRepository creditNoteRepository;
    @InjectMocks PaymentReceiptService service;

    private static final LocalDate ON = LocalDate.of(2026, 9, 19);
    private static final LocalDate TODAY = FinancialYear.today();

    @BeforeEach
    void signIn() {
        signInAs("AC1", UserType.ACCOUNTANT);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void signInAs(String id, UserType type) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal(id, "user", type, "T1"), null, List.of()));
    }

    private static Invoice invoice(String total) {
        BigDecimal t = new BigDecimal(total);
        return Invoice.builder().id("I1").invoiceNumber("INV-1").clientId("K1").clientName("Asha").bookingId("B1").agentId("A1")
                .status(InvoiceLifecycle.ISSUED).currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .grandTotal(t).grandTotalInr(t).balanceDue(t).balanceDueInr(t).build();
    }

    private static PaymentReceiptRequest req(String invoiceId, String amount) {
        PaymentReceiptRequest r = new PaymentReceiptRequest();
        r.setInvoiceId(invoiceId);
        r.setAmount(new BigDecimal(amount));
        r.setPaymentMode(PaymentMode.BANK_TRANSFER);
        r.setReceivedOn(ON);
        return r;
    }

    private static PaymentReceipt deposit(String id, String bookingId, String amount) {
        BigDecimal a = new BigDecimal(amount);
        return PaymentReceipt.builder().id(id).clientId("K1").bookingId(bookingId).direction(ReceiptDirection.RECEIPT)
                .isAdvance(true).amount(a).amountInr(a).build();
    }

    private List<PaymentReceipt> savedReceipts(int times) {
        ArgumentCaptor<PaymentReceipt> saved = ArgumentCaptor.forClass(PaymentReceipt.class);
        verify(paymentReceiptRepository, times(times)).save(saved.capture());
        return saved.getAllValues();
    }

    private JournalPosting postedJournal() {
        ArgumentCaptor<JournalPosting> j = ArgumentCaptor.forClass(JournalPosting.class);
        verify(journalService).post(j.capture());
        return j.getValue();
    }

    // ------------------------------------------------------------------ record

    @Test
    void aReceiptCannotBeDatedAheadOrAgainstAnUnknownInvoice() {
        PaymentReceiptRequest ahead = req("I1", "10");
        ahead.setReceivedOn(TODAY.plusDays(1));
        when(invoiceRepository.findByIdForUpdate("I9")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.record(ahead)).hasMessage("The date received can't be in the future");
        assertThatThrownBy(() -> service.record(req("I9", "10"))).hasMessage("Invoice not found: I9");
    }

    @Test
    void aBlankInvoiceIdIsADepositThatNeedsAClient() {
        PaymentReceiptRequest blank = req(" ", "10");
        PaymentReceiptRequest blankClient = req(null, "10");
        blankClient.setClientId(" ");

        assertThatThrownBy(() -> service.record(blank)).hasMessage("clientId is required when invoiceId is omitted");
        assertThatThrownBy(() -> service.record(blankClient)).hasMessage("clientId is required when invoiceId is omitted");
        verifyNoInteractions(paymentReceiptRepository);
    }

    @Test
    void theWholeBalanceOfACombinedInvoiceIsSplitIntoOneReceiptPerBooking() {
        Invoice inv = invoice("1000.00");
        when(invoiceRepository.findByIdForUpdate("I1")).thenReturn(Optional.of(inv));
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(inv));
        when(invoiceLineSource.bookingIdsOf(inv)).thenReturn(List.of("B1", "B2", "B3"));
        when(bookingAccountingSync.positionOn(inv, "B1", null)).thenReturn(new BookingAccountingSync.Position(BigDecimal.ZERO, new BigDecimal("50")));
        when(bookingAccountingSync.positionOn(inv, "B2", null)).thenReturn(new BookingAccountingSync.Position(new BigDecimal("300.00"), BigDecimal.ZERO));
        when(bookingAccountingSync.positionOn(inv, "B3", null)).thenReturn(new BookingAccountingSync.Position(new BigDecimal("700.00"), BigDecimal.ZERO));
        when(documentNumberService.next(DocumentKind.RECEIPT, ON)).thenReturn("RCP-1", "RCP-2");
        when(paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc("I1")).thenReturn(List.of());

        PaymentReceiptResponse last = service.record(req("I1", "1000.00"));

        List<PaymentReceipt> saved = savedReceipts(2);
        assertThat(saved).extracting(PaymentReceipt::getBookingId).containsExactly("B2", "B3");
        assertThat(saved).extracting(PaymentReceipt::getAmount).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("300.00"), new BigDecimal("700.00"));
        assertThat(last.getReceiptNumber()).isEqualTo("RCP-2");
    }

    @Test
    void aPaymentNamedForABookingNotOnTheInvoiceIsRefused() {
        Invoice inv = invoice("1000.00");
        when(invoiceRepository.findByIdForUpdate("I1")).thenReturn(Optional.of(inv));
        when(invoiceLineSource.bookingIdsOf(inv)).thenReturn(List.of("B1"));
        PaymentReceiptRequest r = req("I1", "100");
        r.setBookingId("B9");

        assertThatThrownBy(() -> service.record(r)).hasMessage("That booking isn't on invoice INV-1");
    }

    @Test
    void aBookingOnAPaidCombinedInvoiceCanStillPayWhatItOwes() {
        Invoice inv = invoice("1000.00");
        inv.setStatus(InvoiceLifecycle.PAID);
        inv.setBalanceDue(BigDecimal.ZERO);
        Invoice again = invoice("1000.00");
        again.setStatus(InvoiceLifecycle.PAID);
        again.setBalanceDue(BigDecimal.ZERO);
        when(invoiceRepository.findByIdForUpdate("I1")).thenReturn(Optional.of(inv), Optional.of(again));
        when(invoiceLineSource.bookingIdsOf(any())).thenReturn(List.of("B1", "B2"));
        com.voyra.crm.entity.CreditNote open = com.voyra.crm.entity.CreditNote.builder()
                .refundableAmount(new BigDecimal("200")).refundedAmount(new BigDecimal("50")).build();
        com.voyra.crm.entity.CreditNote overRefunded = com.voyra.crm.entity.CreditNote.builder()
                .refundableAmount(new BigDecimal("10")).refundedAmount(new BigDecimal("30")).build();
        when(creditNoteRepository.findByInvoiceIdAndStatus("I1", com.voyra.crm.enums.CreditNoteStatus.ISSUED)).thenReturn(List.of(open, overRefunded));
        when(bookingAccountingSync.positionOn(any(), eq("B2"), eq(null))).thenReturn(new BookingAccountingSync.Position(new BigDecimal("400"), BigDecimal.ZERO));
        when(documentNumberService.next(DocumentKind.RECEIPT, ON)).thenReturn("RCP-1");
        when(paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc("I1")).thenReturn(List.of());
        PaymentReceiptRequest r = req("I1", "150");
        r.setBookingId("B2");

        PaymentReceiptResponse res = service.record(r);

        // owed min(400, 0 + 150 open refund) = 150
        assertThat(res.getBookingId()).isEqualTo("B2");
        PaymentReceiptRequest tooMuch = req("I1", "150.01");
        tooMuch.setBookingId("B2");
        assertThatThrownBy(() -> service.record(tooMuch)).hasMessageStartingWith("This booking owes only 150");
    }

    @Test
    void aProformaAdvanceNamedForABookingIsHeldAndNeverSettles() {
        Invoice proforma = invoice("1000.00");
        proforma.setStatus(InvoiceLifecycle.PROFORMA_ISSUED);
        when(invoiceRepository.findByIdForUpdate("I1")).thenReturn(Optional.of(proforma));
        when(invoiceLineSource.bookingIdsOf(proforma)).thenReturn(List.of("B1", "B2"));
        when(documentNumberService.next(DocumentKind.RECEIPT, ON)).thenReturn("RCP-1");
        PaymentReceiptRequest r = req("I1", "5000");
        r.setBookingId("B2");
        r.setPaymentMode(PaymentMode.CASH);

        PaymentReceiptResponse res = service.record(r);

        assertThat(res.getIsAdvance()).isTrue();
        JournalPosting j = postedJournal();
        assertThat(j.purpose()).isEqualTo(JournalPurpose.RECEIPT_ADVANCE);
        assertThat(j.lines().get(0).accountCode()).isEqualTo(SystemAccount.CASH_IN_HAND.code());
        assertThat(j.lines().get(1).accountCode()).isEqualTo(SystemAccount.CLIENT_ADVANCES_HELD.code());
        verify(invoiceRepository, never()).save(any());
        verify(bookingAccountingSync).syncPayment("B1");
        verify(bookingAccountingSync).syncPayment("B2");
    }

    @Test
    void anUnnamedProformaAdvanceOnSeveralBookingsMustNameOne() {
        Invoice proforma = invoice("1000.00");
        proforma.setStatus(InvoiceLifecycle.PROFORMA_ISSUED);
        when(invoiceRepository.findByIdForUpdate("I1")).thenReturn(Optional.of(proforma));
        when(invoiceLineSource.bookingIdsOf(proforma)).thenReturn(List.of("B1", "B2"));

        assertThatThrownBy(() -> service.record(req("I1", "1000.00")))
                .hasMessage("This invoice bills several bookings - pick the booking this payment is for");
    }

    @Test
    void anInvoiceWithNoBalanceFigureIsNotCapped() {
        Invoice inv = invoice("1000.00");
        inv.setBalanceDue(null);
        when(invoiceRepository.findByIdForUpdate("I1")).thenReturn(Optional.of(inv));
        when(invoiceLineSource.bookingIdsOf(inv)).thenReturn(List.of("B1"));
        when(documentNumberService.next(DocumentKind.RECEIPT, ON)).thenReturn("RCP-1");
        when(paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc("I1")).thenReturn(List.of(
                deposit("R0", "B1", "300.00"),
                PaymentReceipt.builder().direction(ReceiptDirection.REFUND).amount(new BigDecimal("100.00")).build()));

        service.record(req("I1", "300.00"));

        assertThat(inv.getAmountReceived()).isEqualByComparingTo("200.00");
        assertThat(inv.getBalanceDue()).isEqualByComparingTo("800.00");
        assertThat(inv.getStatus()).isEqualTo(InvoiceLifecycle.PARTIALLY_PAID);
    }

    @Test
    void aGatewayFeeThatRoundsToNothingIsNotSplitOut() {
        Invoice inv = invoice("1000.00");
        when(invoiceRepository.findByIdForUpdate("I1")).thenReturn(Optional.of(inv));
        when(invoiceLineSource.bookingIdsOf(inv)).thenReturn(List.of("B1"));
        when(documentNumberService.next(DocumentKind.RECEIPT, ON)).thenReturn("RCP-1");
        PaymentReceiptRequest r = req("I1", "100.00");
        r.setGatewayFee(new BigDecimal("0.004"));

        service.record(r);

        JournalPosting j = postedJournal();
        assertThat(j.purpose()).isEqualTo(JournalPurpose.RECEIPT_AGAINST_INVOICE);
        assertThat(j.lines()).hasSize(2);
    }

    @Test
    void aZeroGatewayFeeLeavesNoGatewayFields() {
        Invoice inv = invoice("1000.00");
        when(invoiceRepository.findByIdForUpdate("I1")).thenReturn(Optional.of(inv));
        when(invoiceLineSource.bookingIdsOf(inv)).thenReturn(List.of("B1"));
        when(documentNumberService.next(DocumentKind.RECEIPT, ON)).thenReturn("RCP-1");
        PaymentReceiptRequest r = req("I1", "100.00");
        r.setGatewayFee(BigDecimal.ZERO);
        r.setGatewayProvider("razorpay");

        PaymentReceiptResponse res = service.record(r);

        assertThat(res.getGatewayProvider()).isNull();
        assertThat(res.getGatewayFeeInr()).isNull();
    }

    @Test
    void aZeroReceiptPostsNoJournal() {
        Invoice inv = invoice("1000.00");
        when(invoiceRepository.findByIdForUpdate("I1")).thenReturn(Optional.of(inv));
        when(invoiceLineSource.bookingIdsOf(inv)).thenReturn(List.of("B1"));
        when(documentNumberService.next(DocumentKind.RECEIPT, ON)).thenReturn("RCP-1");

        service.record(req("I1", "0.00"));

        verify(journalService, never()).post(any());
        verify(customerLedgerService).post(any());
    }

    @Test
    void aBlankBookingOnASingleBookingInvoiceIsThatBooking() {
        Invoice inv = invoice("1000.00");
        when(invoiceRepository.findByIdForUpdate("I1")).thenReturn(Optional.of(inv));
        when(invoiceLineSource.bookingIdsOf(inv)).thenReturn(List.of("B1"));
        when(documentNumberService.next(DocumentKind.RECEIPT, ON)).thenReturn("RCP-1");
        PaymentReceiptRequest r = req("I1", "100.00");
        r.setBookingId(" ");

        assertThat(service.record(r).getBookingId()).isEqualTo("B1");
    }

    // ------------------------------------------------------------------ deposits

    @Test
    void aDepositForABookingMustBeThatClientsBooking() {
        when(clientService.findAccessibleClient("K1")).thenReturn(Client.builder().id("K1").name("Asha").build());
        when(bookingRepository.findById("B9")).thenReturn(Optional.empty());
        when(bookingRepository.findById("B2")).thenReturn(Optional.of(Booking.builder().id("B2").clientId("K2").build()));
        PaymentReceiptRequest unknown = req(null, "100");
        unknown.setClientId("K1");
        unknown.setBookingId("B9");
        PaymentReceiptRequest other = req(null, "100");
        other.setClientId("K1");
        other.setBookingId("B2");

        assertThatThrownBy(() -> service.record(unknown)).hasMessage("Booking not found: B9");
        assertThatThrownBy(() -> service.record(other)).hasMessage("This booking belongs to another client");
        verify(paymentReceiptRepository, never()).save(any());
    }

    @Test
    void aDepositForTheClientsOwnBookingIsHeldForIt() {
        when(clientService.findAccessibleClient("K1")).thenReturn(Client.builder().id("K1").name("Asha").build());
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(Booking.builder().id("B1").clientId("K1").build()));
        when(documentNumberService.next(DocumentKind.RECEIPT, ON)).thenReturn("RCP-1", "RCP-2");
        PaymentReceiptRequest forBooking = req(null, "500.00");
        forBooking.setClientId("K1");
        forBooking.setBookingId("B1");
        PaymentReceiptRequest blankBooking = req(null, "200.00");
        blankBooking.setClientId("K1");
        blankBooking.setBookingId(" ");

        service.record(forBooking);
        service.record(blankBooking);

        List<PaymentReceipt> saved = savedReceipts(2);
        assertThat(saved.get(0).getBookingId()).isEqualTo("B1");
        assertThat(saved.get(0).getIsAdvance()).isTrue();
        assertThat(saved.get(0).getInvoiceId()).isNull();
        assertThat(saved.get(1).getBookingId()).isNull();
    }

    // ------------------------------------------------------------------ refunds and wallet credit

    private RefundSettlementRequest refund(PaymentMode mode) {
        RefundSettlementRequest r = new RefundSettlementRequest();
        r.setAmount(new BigDecimal("400.00"));
        r.setPaidOn(ON);
        r.setPaymentMode(mode);
        r.setReference("UTR1");
        return r;
    }

    @Test
    void aRefundToTheClientsAccountPostsNoJournal() {
        Booking b = Booking.builder().id("B1").clientId("K1").build();
        when(documentNumberService.next(DocumentKind.RECEIPT, ON)).thenReturn("RCP-9");

        PaymentReceiptResponse res = service.refundAdvance(b, "PF1", refund(PaymentMode.BANK_TRANSFER), true);

        assertThat(res.getDirection()).isEqualTo(ReceiptDirection.REFUND);
        assertThat(res.getNotes()).isEqualTo("Refund on cancelled booking, credited to client account");
        assertThat(res.getInvoiceId()).isEqualTo("PF1");
        ArgumentCaptor<LedgerPosting> ledger = ArgumentCaptor.forClass(LedgerPosting.class);
        verify(customerLedgerService).post(ledger.capture());
        assertThat(ledger.getValue().entryType()).isEqualTo(LedgerEntryType.REFUND_PAID);
        assertThat(ledger.getValue().narration()).isEqualTo("Refund credited to the client's account - RCP-9 (cancelled booking)");
        verify(journalService, never()).post(any());
        verify(bookingAccountingSync).syncPayment("B1");
        verify(auditService).recordCreate(AuditEntityType.PAYMENT_RECEIPT, res.getId(), "RCP-9");
    }

    @Test
    void aCashOrBankRefundPaysOutOfClientAdvancesHeld() {
        Booking b = Booking.builder().id("B1").clientId("K1").build();
        when(documentNumberService.next(DocumentKind.RECEIPT, ON)).thenReturn("RCP-9", "RCP-10");

        PaymentReceiptResponse cash = service.refundAdvance(b, null, refund(PaymentMode.CASH), false);
        service.refundAdvance(b, null, refund(PaymentMode.UPI), false);

        assertThat(cash.getNotes()).isEqualTo("Refund on cancelled booking");
        ArgumentCaptor<JournalPosting> j = ArgumentCaptor.forClass(JournalPosting.class);
        verify(journalService, times(2)).post(j.capture());
        JournalPosting first = j.getAllValues().get(0);
        assertThat(first.purpose()).isEqualTo(JournalPurpose.CLIENT_REFUND_PAID);
        assertThat(first.narration()).isEqualTo("Refund paid to the client by the agency - RCP-9 (cancelled booking)");
        assertThat(first.lines().get(0).accountCode()).isEqualTo(SystemAccount.CLIENT_ADVANCES_HELD.code());
        assertThat(first.lines().get(0).debitAmount()).isEqualByComparingTo("400.00");
        assertThat(first.lines().get(1).accountCode()).isEqualTo(SystemAccount.CASH_IN_HAND.code());
        assertThat(j.getAllValues().get(1).lines().get(1).accountCode()).isEqualTo(SystemAccount.BANK_ACCOUNTS.code());
    }

    @Test
    void creditingTheWalletIsADepositWithNoBooking() {
        when(documentNumberService.next(DocumentKind.RECEIPT, ON)).thenReturn("RCP-5");

        PaymentReceiptResponse res = service.creditWallet("K1", new BigDecimal("250.00"), ON, "Refund of BK1 to wallet");

        assertThat(res.getBookingId()).isNull();
        assertThat(res.getIsAdvance()).isTrue();
        assertThat(res.getPaymentMode()).isEqualTo(PaymentMode.ADJUSTMENT);
        ArgumentCaptor<LedgerPosting> ledger = ArgumentCaptor.forClass(LedgerPosting.class);
        verify(customerLedgerService).post(ledger.capture());
        assertThat(ledger.getValue().entryType()).isEqualTo(LedgerEntryType.PAYMENT_RECEIVED);
        assertThat(ledger.getValue().creditAmount()).isEqualByComparingTo("250.00");
    }

    // ------------------------------------------------------------------ wallet

    @Test
    void applyingADepositNeedsAnInvoiceAndAnActiveDepositOfTheSameClient() {
        PaymentReceiptApplyAdvanceRequest r = new PaymentReceiptApplyAdvanceRequest();
        r.setAdvanceReceiptId("D1");
        r.setAmount(BigDecimal.TEN);
        when(invoiceRepository.findById("I9")).thenReturn(Optional.empty());
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(invoice("100")));
        assertThatThrownBy(() -> service.applyAdvance("I9", r)).hasMessage("Invoice not found: I9");

        when(paymentReceiptRepository.findById("D1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.applyAdvance("I1", r)).hasMessage("Deposit not found: D1");

        PaymentReceipt notAdvance = deposit("D1", null, "10");
        notAdvance.setIsAdvance(false);
        PaymentReceipt onInvoice = deposit("D1", null, "10");
        onInvoice.setInvoiceId("I2");
        PaymentReceipt reversed = deposit("D1", null, "10");
        reversed.setReversedAt(LocalDateTime.now());
        PaymentReceipt otherClient = deposit("D1", null, "10");
        otherClient.setClientId("K2");
        when(paymentReceiptRepository.findById("D1")).thenReturn(Optional.of(notAdvance), Optional.of(onInvoice),
                Optional.of(reversed), Optional.of(otherClient));
        assertThatThrownBy(() -> service.applyAdvance("I1", r)).hasMessage("This receipt is not an active deposit on account");
        assertThatThrownBy(() -> service.applyAdvance("I1", r)).hasMessage("This receipt is not an active deposit on account");
        assertThatThrownBy(() -> service.applyAdvance("I1", r)).hasMessage("This receipt is not an active deposit on account");
        assertThatThrownBy(() -> service.applyAdvance("I1", r)).hasMessage("This deposit belongs to a different client");
        verify(paymentReceiptRepository, never()).save(any());
    }

    @Test
    void autoApplyDoesNothingWithAnEmptyWalletOrASettledInvoice() {
        Invoice inv = invoice("1000.00");
        Invoice settled = invoice("1000.00");
        settled.setId("I2");
        settled.setClientId("K2");
        settled.setBalanceDue(BigDecimal.ZERO);
        when(paymentReceiptRepository.findByClientIdAndInvoiceIdIsNullAndIsAdvanceTrueOrderByReceivedOnAsc("K1")).thenReturn(List.of());
        PaymentReceipt k2 = deposit("D1", null, "100");
        k2.setClientId("K2");
        when(paymentReceiptRepository.findByClientIdAndInvoiceIdIsNullAndIsAdvanceTrueOrderByReceivedOnAsc("K2")).thenReturn(List.of(k2));

        service.applyAvailableWallet(inv);
        service.applyAvailableWallet(settled);

        verify(paymentReceiptRepository, never()).save(any());
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void autoApplyOnAForeignInvoiceDrawsAtItsRateRoundedDown() {
        Invoice inv = invoice("1000.00");
        inv.setCurrencyCode("USD");
        inv.setFxRateToInr(new BigDecimal("3"));
        when(paymentReceiptRepository.findByClientIdAndInvoiceIdIsNullAndIsAdvanceTrueOrderByReceivedOnAsc("K1"))
                .thenReturn(List.of(deposit("D1", null, "1000.00")));
        when(paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc("I1")).thenReturn(List.of());

        service.applyAvailableWallet(inv);

        PaymentReceipt applied = savedReceipts(1).get(0);
        assertThat(applied.getAmount()).isEqualByComparingTo("333.33");
        assertThat(applied.getAmountInr()).isEqualByComparingTo("999.99");
        assertThat(applied.getBookingId()).isEqualTo("B1");
        assertThat(applied.getAppliedFromAdvance()).isTrue();
        JournalPosting j = postedJournal();
        assertThat(j.purpose()).isEqualTo(JournalPurpose.ADVANCE_APPLIED);
        assertThat(j.narration()).isEqualTo("Wallet applied to invoice INV-1");
    }

    @Test
    void autoApplyOnAnUnnumberedCombinedInvoiceSkipsBookingsThatOweNothing() {
        Invoice inv = invoice("900.00");
        inv.setInvoiceNumber(null);
        when(invoiceLineSource.bookingIdsOf(inv)).thenReturn(List.of("B1", "B2", "B3"));
        when(paymentReceiptRepository.findByClientIdAndInvoiceIdIsNullAndIsAdvanceTrueOrderByReceivedOnAsc("K1"))
                .thenReturn(List.of(deposit("D1", null, "1000.00")));
        when(bookingAccountingSync.positionOn(inv, "B1", null)).thenReturn(new BookingAccountingSync.Position(BigDecimal.ZERO, BigDecimal.ZERO));
        when(bookingAccountingSync.positionOn(inv, "B2", null)).thenReturn(new BookingAccountingSync.Position(new BigDecimal("500.00"), BigDecimal.ZERO));
        when(paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc("I1")).thenReturn(List.of());

        service.applyAvailableWallet(inv);

        List<PaymentReceipt> saved = savedReceipts(2);
        assertThat(saved).extracting(PaymentReceipt::getBookingId).containsExactly("B2", "B3");
        assertThat(saved).extracting(PaymentReceipt::getAmount).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("500.00"), new BigDecimal("400.00"));
        ArgumentCaptor<JournalPosting> j = ArgumentCaptor.forClass(JournalPosting.class);
        verify(journalService, times(2)).post(j.capture());
        assertThat(j.getValue().narration()).isEqualTo("Wallet applied to invoice I1");
    }

    @Test
    void aCombinedInvoiceWhollyCoveredByOneBookingsHeldAdvanceDrawsOnlyThat() {
        Invoice inv = invoice("300.00");
        when(invoiceLineSource.bookingIdsOf(inv)).thenReturn(List.of("B1", "B2"));
        when(paymentReceiptRepository.findByClientIdAndInvoiceIdIsNullAndIsAdvanceTrueOrderByReceivedOnAsc("K1"))
                .thenReturn(List.of(deposit("D1", "B1", "300.00")));
        when(paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc("I1")).thenReturn(List.of());

        service.applyAvailableWallet(inv);

        PaymentReceipt only = savedReceipts(1).get(0);
        assertThat(only.getBookingId()).isEqualTo("B1");
        assertThat(only.getAmount()).isEqualByComparingTo("300.00");
        verifyNoInteractions(bookingAccountingSync);
    }

    @Test
    void applyingMoreThanTheWalletToAnInvoiceWithNoRateIsRefused() {
        Invoice inv = invoice("1000.00");
        inv.setFxRateToInr(null);
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(inv));
        when(paymentReceiptRepository.findById("D1")).thenReturn(Optional.of(deposit("D1", null, "100.00")));
        when(paymentReceiptRepository.findByClientIdAndInvoiceIdIsNullAndIsAdvanceTrueOrderByReceivedOnAsc("K1"))
                .thenReturn(List.of(deposit("D1", null, "100.00")));
        PaymentReceiptApplyAdvanceRequest r = new PaymentReceiptApplyAdvanceRequest();
        r.setAdvanceReceiptId("D1");
        r.setAmount(new BigDecimal("100.01"));

        assertThatThrownBy(() -> service.applyAdvance("I1", r)).hasMessage("Only 100.00 of the wallet is available for this invoice");
        verify(paymentReceiptRepository, never()).save(any());
    }

    @Test
    void anInvoiceWithNoBookingsDrawsUnderItsOwnBooking() {
        Invoice inv = invoice("100.00");
        when(paymentReceiptRepository.findByClientIdAndInvoiceIdIsNullAndIsAdvanceTrueOrderByReceivedOnAsc("K1"))
                .thenReturn(List.of(deposit("D1", null, "40.00")));
        when(paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc("I1")).thenReturn(List.of());

        service.applyAvailableWallet(inv);

        assertThat(savedReceipts(1).get(0).getBookingId()).isEqualTo("B1");
        assertThat(inv.getStatus()).isEqualTo(InvoiceLifecycle.ISSUED);
    }

    @Test
    void availableWalletLeavesOutMoneyHeldForOtherBookings() {
        PaymentReceipt reversed = deposit("D2", "B2", "999");
        reversed.setReversedAt(LocalDateTime.now());
        PaymentReceipt reversal = deposit("D3", "B2", "-50");
        reversal.setReversesReceiptId("D0");
        when(paymentReceiptRepository.findByClientIdAndInvoiceIdIsNullAndIsAdvanceTrueOrderByReceivedOnAsc("K1")).thenReturn(List.of(
                deposit("D1", "B1", "300"), deposit("D4", null, "500"), reversed, reversal, deposit("D5", "B2", "200")));
        PaymentReceipt drawnB2 = deposit("A1", "B2", "50");
        PaymentReceipt drawnNoBooking = deposit("A2", null, "10");
        PaymentReceipt drawnReversed = deposit("A3", "B2", "70");
        drawnReversed.setReversedAt(LocalDateTime.now());
        PaymentReceipt drawnReversal = deposit("A4", "B2", "-70");
        drawnReversal.setReversesReceiptId("A3");
        PaymentReceipt drawnElsewhere = deposit("A5", "B7", "20");
        when(paymentReceiptRepository.findByClientIdAndAppliedFromAdvanceTrueOrderByReceivedOnAsc("K1"))
                .thenReturn(List.of(drawnB2, drawnNoBooking, drawnReversed, drawnReversal, drawnElsewhere));

        // pool 1000 - drawn 80 = 920; held B1 300, B2 150
        assertThat(service.availableWalletFor("K1", "B1")).isEqualByComparingTo("770");
        assertThat(service.availableWalletFor("K1", (String) null)).isEqualByComparingTo("470");
        assertThat(service.heldForBooking("K1", "B2")).isEqualByComparingTo("150");
        assertThat(service.heldForBooking("K1", "B9")).isEqualByComparingTo("0");
        assertThat(service.heldForBooking(null, "B2")).isEqualByComparingTo("0");
        assertThat(service.heldForBooking("K1", null)).isEqualByComparingTo("0");
        assertThat(service.availableWalletFor(null, List.of("B1"))).isEqualByComparingTo("0");
    }

    @Test
    void walletForAnInvoiceOffersTheOldestActiveDeposit() {
        Invoice inv = invoice("100");
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(inv));
        when(invoiceRepository.findById("I9")).thenReturn(Optional.empty());
        PaymentReceipt reversed = deposit("D1", null, "10");
        reversed.setReversedAt(LocalDateTime.now());
        PaymentReceipt refundOut = deposit("D2", null, "5");
        refundOut.setDirection(ReceiptDirection.REFUND);
        when(paymentReceiptRepository.findByClientIdAndInvoiceIdIsNullAndIsAdvanceTrueOrderByReceivedOnAsc("K1"))
                .thenReturn(List.of(reversed, refundOut, deposit("D3", null, "100")));

        InvoiceWalletResponse w = service.walletFor("I1");

        assertThat(w.getAdvanceReceiptId()).isEqualTo("D3");
        assertThat(w.getAvailableInr()).isEqualByComparingTo("95");
        assertThatThrownBy(() -> service.walletFor("I9")).hasMessage("Invoice not found: I9");
    }

    @Test
    void walletForAnInvoiceWithNoDepositOffersNone() {
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(invoice("100")));

        InvoiceWalletResponse w = service.walletFor("I1");

        assertThat(w.getAdvanceReceiptId()).isNull();
        assertThat(w.getAvailableInr()).isEqualByComparingTo("0");
    }

    // ------------------------------------------------------------------ reverse

    @Test
    void reversingAReceiptWhoseInvoiceIsGoneIsAnError() {
        PaymentReceipt r = deposit("R1", "B1", "100");
        r.setInvoiceId("I9");
        when(paymentReceiptRepository.findById("R1")).thenReturn(Optional.of(r));
        when(paymentReceiptRepository.findById("R9")).thenReturn(Optional.empty());
        when(invoiceRepository.findById("I9")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reverse("R1", "x")).hasMessage("Invoice not found: I9");
        assertThatThrownBy(() -> service.reverse("R9", "x")).hasMessage("Receipt not found: R9");
    }

    @Test
    void reversingAWalletDrawdownIsAPlainUndoWithNoNumberOrLedgerPost() {
        Invoice inv = invoice("100.00");
        PaymentReceipt drawn = deposit("R1", "B1", "40.00");
        drawn.setInvoiceId("I1");
        drawn.setIsAdvance(false);
        drawn.setAppliedFromAdvance(true);
        when(paymentReceiptRepository.findById("R1")).thenReturn(Optional.of(drawn));
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(inv));
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(JournalSourceType.RECEIPT, "R1", JournalPurpose.ADVANCE_APPLIED))
                .thenReturn(Optional.of(JournalEntry.builder().id("JE1").build()));
        when(paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc("I1")).thenReturn(List.of());
        when(invoiceLineSource.bookingIdsOf(inv)).thenReturn(List.of("B1"));

        PaymentReceiptResponse res = service.reverse("R1", "wrong invoice");

        assertThat(res.getReceiptNumber()).isNull();
        assertThat(res.getFinancialYear()).isNull();
        assertThat(res.getAmount()).isEqualByComparingTo("-40.00");
        assertThat(res.getAppliedFromAdvance()).isTrue();
        verify(documentNumberService, never()).next(any(), any());
        verify(customerLedgerService, never()).post(any());
        verify(journalService).reverse("JE1", "Receipt R1 reversed");
        verify(auditService).recordCreate(AuditEntityType.PAYMENT_RECEIPT, res.getId(), res.getId());
        assertThat(drawn.getReversedBy()).isEqualTo("AC1");
        verify(bookingAccountingSync).syncPayment("B1");
    }

    @Test
    void reversingADepositNeedsNoInvoiceSettlement() {
        PaymentReceipt dep = deposit("R1", null, "500.00");
        dep.setReceiptNumber("RCP-1");
        when(paymentReceiptRepository.findById("R1")).thenReturn(Optional.of(dep));
        when(documentNumberService.next(DocumentKind.RECEIPT, TODAY)).thenReturn("RCP-2");
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(JournalSourceType.RECEIPT, "R1", JournalPurpose.RECEIPT_ADVANCE))
                .thenReturn(Optional.of(JournalEntry.builder().id("JE7").build()));
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(JournalSourceType.RECEIPT, "R1", JournalPurpose.RECEIPT_AGAINST_INVOICE))
                .thenReturn(Optional.empty());
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(JournalSourceType.RECEIPT, "R1", JournalPurpose.GATEWAY_RECEIPT))
                .thenReturn(Optional.empty());

        PaymentReceiptResponse res = service.reverse("R1", "bounced");

        assertThat(res.getReceiptNumber()).isEqualTo("RCP-2");
        assertThat(res.getFinancialYear()).isEqualTo(FinancialYear.of(TODAY));
        ArgumentCaptor<LedgerPosting> ledger = ArgumentCaptor.forClass(LedgerPosting.class);
        verify(customerLedgerService).post(ledger.capture());
        assertThat(ledger.getValue().narration()).isEqualTo("Receipt RCP-2 reverses RCP-1");
        assertThat(ledger.getValue().debitAmount()).isEqualByComparingTo("500.00");
        verify(journalService).reverse("JE7", "Receipt RCP-1 reversed");
        verifyNoInteractions(invoiceRepository, bookingAccountingSync);
    }

    @Test
    void reversingAProformaAdvanceSyncsButDoesNotSettleAndNamesAMissingOriginalById() {
        Invoice proforma = invoice("100.00");
        proforma.setStatus(InvoiceLifecycle.PROFORMA_ISSUED);
        PaymentReceipt adv = deposit("R1", "B1", "100.00");
        adv.setInvoiceId("I1");
        when(paymentReceiptRepository.findById("R1")).thenReturn(Optional.of(adv), Optional.empty());
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(proforma));
        when(documentNumberService.next(DocumentKind.RECEIPT, TODAY)).thenReturn("RCP-2");
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(any(), eq("R1"), any())).thenReturn(Optional.empty());
        when(invoiceLineSource.bookingIdsOf(proforma)).thenReturn(List.of("B1"));

        service.reverse("R1", "x");

        ArgumentCaptor<LedgerPosting> ledger = ArgumentCaptor.forClass(LedgerPosting.class);
        verify(customerLedgerService).post(ledger.capture());
        assertThat(ledger.getValue().narration()).isEqualTo("Receipt RCP-2 reverses R1");
        verify(invoiceRepository, never()).save(any());
        verify(bookingAccountingSync).syncPayment("B1");
    }

    // ------------------------------------------------------------------ read

    @Test
    void anAgentReadsOnlyReceiptsOnTheirOwnInvoices() {
        signInAs("A1", UserType.AGENT);
        PaymentReceipt mine = deposit("R1", "B1", "10");
        mine.setInvoiceId("I1");
        PaymentReceipt theirs = deposit("R2", "B1", "10");
        theirs.setInvoiceId("I2");
        PaymentReceipt gone = deposit("R3", "B1", "10");
        gone.setInvoiceId("I3");
        PaymentReceipt noInvoice = deposit("R4", "B1", "10");
        when(paymentReceiptRepository.findById("R1")).thenReturn(Optional.of(mine));
        when(paymentReceiptRepository.findById("R2")).thenReturn(Optional.of(theirs));
        when(paymentReceiptRepository.findById("R3")).thenReturn(Optional.of(gone));
        when(paymentReceiptRepository.findById("R4")).thenReturn(Optional.of(noInvoice));
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(invoice("10")));
        Invoice other = invoice("10");
        other.setAgentId("A2");
        when(invoiceRepository.findById("I2")).thenReturn(Optional.of(other));
        when(invoiceRepository.findById("I3")).thenReturn(Optional.empty());

        assertThat(service.get("R1").getId()).isEqualTo("R1");
        for (String id : List.of("R2", "R3", "R4")) {
            assertThatThrownBy(() -> service.get(id)).isInstanceOf(AccessDeniedException.class)
                    .hasMessage("This receipt is not accessible to you");
        }
    }

    @Test
    void theReceiptVoucherNamesItsInvoiceWhenThereIsOne() {
        PaymentReceipt onInvoice = deposit("R1", "B1", "10");
        onInvoice.setInvoiceId("I1");
        onInvoice.setReceiptNumber("RCP-1");
        onInvoice.setReceivedOn(ON);
        onInvoice.setPaymentMode(PaymentMode.UPI);
        PaymentReceipt missingInvoice = deposit("R2", "B1", "10");
        missingInvoice.setInvoiceId("I9");
        missingInvoice.setReceiptNumber("RCP-2");
        missingInvoice.setReceivedOn(ON);
        missingInvoice.setPaymentMode(PaymentMode.UPI);
        PaymentReceipt dep = deposit("R3", null, "10");
        dep.setReceiptNumber("RCP-3");
        dep.setReceivedOn(ON);
        dep.setPaymentMode(PaymentMode.CASH);
        when(paymentReceiptRepository.findById("R1")).thenReturn(Optional.of(onInvoice));
        when(paymentReceiptRepository.findById("R2")).thenReturn(Optional.of(missingInvoice));
        when(paymentReceiptRepository.findById("R3")).thenReturn(Optional.of(dep));
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(invoice("10")));
        when(invoiceRepository.findById("I9")).thenReturn(Optional.empty());

        for (String id : List.of("R1", "R2", "R3")) {
            assertThat(new String(service.getPdf(id), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        }
        verify(invoiceRepository).findById("I1");
        verify(invoiceRepository).findById("I9");
    }

    @Test
    @SuppressWarnings("unchecked")
    void anAgentWithNoInvoicesListsNothingAndFiltersApply() {
        signInAs("A1", UserType.AGENT);
        when(invoiceRepository.findIdsByAgentId("A1")).thenReturn(List.of(), List.of("I1"));
        Pageable page = PageRequest.of(0, 10);
        when(paymentReceiptRepository.findAll(any(Specification.class), eq(page)))
                .thenReturn(new PageImpl<>(List.of(deposit("R1", "B1", "10")), page, 1));
        when(paymentReceiptRepository.findAll(any(Specification.class), eq(Sort.by(Sort.Direction.DESC, "receivedOn"))))
                .thenReturn(List.of(deposit("R2", "B1", "10")));

        PagedResponse<PaymentReceiptResponse> paged = (PagedResponse<PaymentReceiptResponse>) service.list("I1", "K1", page);
        List<PaymentReceiptResponse> all = (List<PaymentReceiptResponse>) service.list(null, null, null);

        assertThat(paged.getContent()).extracting(PaymentReceiptResponse::getId).containsExactly("R1");
        assertThat(all).extracting(PaymentReceiptResponse::getId).containsExactly("R2");
        ArgumentCaptor<Specification<PaymentReceipt>> spec = ArgumentCaptor.forClass(Specification.class);
        verify(paymentReceiptRepository).findAll(spec.capture(), eq(page));
        Root<PaymentReceipt> root = mock(Root.class, RETURNS_DEEP_STUBS);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        spec.getValue().toPredicate(root, query, cb);
        verify(root.get("invoiceId")).in(List.of("__none__"));
        verify(cb).equal(any(), eq("I1"));
        verify(cb).equal(any(), eq("K1"));
    }

    @Test
    void anAccountantsListIsUnscoped() {
        when(paymentReceiptRepository.findAll(org.mockito.ArgumentMatchers.<Specification<PaymentReceipt>>any(),
                eq(Sort.by(Sort.Direction.DESC, "receivedOn")))).thenReturn(List.of());

        assertThat((List<?>) service.list(null, null, null)).isEmpty();
        verify(invoiceRepository, never()).findIdsByAgentId(any());
    }
}
