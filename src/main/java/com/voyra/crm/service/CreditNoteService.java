package com.voyra.crm.service;

import com.voyra.crm.dto.AuditChange;
import com.voyra.crm.dto.CreditNoteRefundRequest;
import com.voyra.crm.dto.CreditNoteRequest;
import com.voyra.crm.dto.CreditNoteResponse;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.dto.PaymentReceiptResponse;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.CreditNote;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.entity.PaymentReceipt;
import com.voyra.crm.enums.AuditEntityType;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.CreditNoteReason;
import com.voyra.crm.enums.CreditNoteStatus;
import com.voyra.crm.enums.DocumentKind;
import com.voyra.crm.enums.InvoiceDocumentType;
import com.voyra.crm.enums.InvoiceLifecycle;
import com.voyra.crm.enums.LedgerEntryType;
import com.voyra.crm.enums.LedgerSourceType;
import com.voyra.crm.enums.ReceiptDirection;
import com.voyra.crm.models.LedgerPosting;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.CreditNoteRepository;
import com.voyra.crm.repository.InvoiceRepository;
import com.voyra.crm.repository.PaymentReceiptRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.CreditNotePdfRenderer;
import com.voyra.crm.util.FinancialYear;
import com.voyra.crm.util.InvoiceLifecyclePolicy;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A credit note is drafted, then issued (allocates its own {@code CN/...} number and locks every
 * tax-reversal figure), then optionally refunded in cash. There is no line-item table: a partial
 * credit is expressed as {@link CreditNoteRequest#getCancellationFee()}, the slice of the
 * invoice's taxable value retained and kept taxable, with everything else reversed pro-rata at
 * the invoice's own persisted CGST/SGST/IGST/TCS amounts - see {@link #computeReversal}.
 *
 * <p>Issuing posts a {@code CREDIT_NOTE_ISSUED} ledger credit and permanently raises the
 * invoice's {@code creditNoteTotal} (never reduced again, even after a full refund - the invoice
 * keeps an honest historical record of what was credited against it). A refund payout instead
 * posts a {@code REFUND_PAID} debit and lowers the invoice's {@code amountReceived} (net cash kept),
 * which brings the negative balance the credit note left behind back to zero. This means {@code CustomerLedgerService}'s documented invariant needs one more
 * term once a refund exists: {@code SUM(debit_inr - credit_inr)} over the ledger equals
 * {@code SUM(invoice.balance_due_inr)} minus {@code SUM(credit_note.refunded_amount)} (converted
 * to INR at each credit note's own locked rate), plus any opening balance.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditNoteService {

    private final CreditNoteRepository creditNoteRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentReceiptRepository paymentReceiptRepository;
    private final BookingRepository bookingRepository;
    private final DocumentNumberService documentNumberService;
    private final AuditService auditService;
    private final CustomerLedgerService customerLedgerService;
    private final BookingAccountingSync bookingAccountingSync;
    private final JournalService journalService;
    private final com.voyra.crm.repository.JournalEntryRepository journalEntryRepository;
    private final com.voyra.crm.repository.JournalLineRepository journalLineRepository;

    @Transactional
    public CreditNoteResponse create(CreditNoteRequest request) {
        Invoice invoice = invoiceRepository.findById(request.getInvoiceId())
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found: " + request.getInvoiceId()));
        if (invoice.getDocumentType() != InvoiceDocumentType.TAX_INVOICE) {
            throw new IllegalStateException("Only a tax invoice can carry a credit note");
        }
        if (invoice.getStatus() != InvoiceLifecycle.ISSUED
                && invoice.getStatus() != InvoiceLifecycle.PARTIALLY_PAID
                && invoice.getStatus() != InvoiceLifecycle.PAID) {
            throw new IllegalStateException("Only an issued invoice can be credited");
        }
        if (request.getReason() == CreditNoteReason.BOOKING_CANCELLED && invoice.getBookingId() != null) {
            Booking booking = bookingRepository.findById(invoice.getBookingId()).orElse(null);
            if (booking != null && booking.getBookingStatus() != BookingStatus.CANCELLED) {
                throw new IllegalStateException("Reason BOOKING_CANCELLED requires the booking to actually be cancelled first");
            }
        }

        BigDecimal cancellationFee = request.getCancellationFee() != null ? request.getCancellationFee() : BigDecimal.ZERO;
        if (cancellationFee.compareTo(invoice.getTaxableValue()) > 0) {
            throw new IllegalArgumentException("cancellationFee cannot exceed the invoice's taxable value");
        }

        Reversal reversal = computeReversal(invoice, cancellationFee, request.getSupplierRetained());
        assertWithinInvoiceValue(invoice, reversal.totalAmount, null);

        LocalDate noteDate = request.getNoteDate() != null ? request.getNoteDate() : LocalDate.now();
        CreditNote note = CreditNote.builder()
                .id(UniqueIdResolver.resolve(creditNoteRepository::existsById))
                .invoiceId(invoice.getId())
                .invoiceNumber(invoice.getInvoiceNumber())
                .clientId(invoice.getClientId())
                .clientName(invoice.getClientName())
                .bookingId(invoice.getBookingId())
                .reason(request.getReason())
                .reasonNote(request.getReasonNote())
                .status(CreditNoteStatus.DRAFT)
                .currencyCode(invoice.getCurrencyCode())
                .fxRateToInr(invoice.getFxRateToInr())
                .taxableValue(reversal.taxableValue)
                .cgstAmount(reversal.cgstAmount)
                .sgstAmount(reversal.sgstAmount)
                .igstAmount(reversal.igstAmount)
                .tcsAmount(reversal.tcsAmount)
                .cancellationFee(cancellationFee)
                .supplierRetained(request.getSupplierRetained())
                .totalAmount(reversal.totalAmount)
                .totalAmountInr(scaleToInr(reversal.totalAmount, invoice.getFxRateToInr()))
                .noteDate(noteDate)
                .createdAt(LocalDateTime.now())
                .createdBy(currentUserId())
                .build();
        creditNoteRepository.save(note);

        log.info("Credit note draft created: id={}, invoiceId={}", note.getId(), invoice.getId());
        return toResponse(note);
    }

    @Transactional
    public CreditNoteResponse issue(String id) {
        CreditNote note = findById(id);
        if (note.getStatus() != CreditNoteStatus.DRAFT) {
            throw new IllegalStateException("Only a draft credit note can be issued");
        }
        Invoice invoice = invoiceRepository.findById(note.getInvoiceId())
                .orElseThrow(() -> new IllegalStateException("Invoice not found: " + note.getInvoiceId()));
        assertWithinInvoiceValue(invoice, note.getTotalAmount(), note.getId());

        LocalDate today = LocalDate.now();
        String number = documentNumberService.next(DocumentKind.CREDIT_NOTE, today);
        String actor = currentUserId();

        note.setCreditNoteNumber(number);
        note.setFinancialYear(FinancialYear.of(today));
        note.setStatus(CreditNoteStatus.ISSUED);
        note.setIssuedAt(LocalDateTime.now());
        note.setIssuedBy(actor);
        // Capped at cash actually receipted on the invoice, not the note's own face value -
        // a credit note issued against a partially (or un-)paid invoice cannot be refunded
        // for more than the agency ever actually collected from the client.
        note.setRefundableAmount(refundableFor(invoice, note.getTotalAmount(), note.getId()));
        creditNoteRepository.save(note);

        invoice.setCreditNoteTotal(invoice.getCreditNoteTotal().add(note.getTotalAmount()));
        BigDecimal balanceDue = invoice.getGrandTotal().subtract(invoice.getAmountReceived()).subtract(invoice.getCreditNoteTotal());
        invoice.setBalanceDue(balanceDue);
        invoice.setBalanceDueInr(scaleToInr(balanceDue, invoice.getFxRateToInr()));
        invoice.setStatus(InvoiceLifecyclePolicy.deriveFromBalance(invoice.getGrandTotal(), balanceDue));
        invoiceRepository.save(invoice);

        customerLedgerService.post(new LedgerPosting(
                note.getClientId(), today, LedgerEntryType.CREDIT_NOTE_ISSUED,
                LedgerSourceType.CREDIT_NOTE, note.getId(), note.getCreditNoteNumber(),
                "Credit note " + note.getCreditNoteNumber() + " against " + invoice.getInvoiceNumber(),
                note.getBookingId(), note.getCurrencyCode(), note.getFxRateToInr(),
                BigDecimal.ZERO, note.getTotalAmount(), BigDecimal.ZERO, note.getTotalAmountInr()));
        postCreditNoteIssuedJournal(note, invoice);
        bookingAccountingSync.syncRefund(note.getBookingId());

        auditService.recordCreate(AuditEntityType.CREDIT_NOTE, note.getId(), note.getCreditNoteNumber());
        log.info("Credit note issued: id={}, number={}, invoiceId={}", note.getId(), number, invoice.getId());
        return toResponse(note);
    }

    @Transactional
    public CreditNoteResponse cancel(String id, String reason) {
        CreditNote note = findById(id);
        if (note.getStatus() == CreditNoteStatus.CANCELLED) {
            throw new IllegalStateException("This credit note is already cancelled");
        }

        String beforeStatus = note.getStatus().name();
        String actor = currentUserId();
        LocalDateTime now = LocalDateTime.now();

        if (note.getStatus() == CreditNoteStatus.ISSUED) {
            if (note.getRefundedAmount().compareTo(BigDecimal.ZERO) > 0) {
                throw new IllegalStateException("This credit note has refunds against it and can no longer be cancelled");
            }
            Invoice invoice = invoiceRepository.findById(note.getInvoiceId())
                    .orElseThrow(() -> new IllegalStateException("Invoice not found: " + note.getInvoiceId()));
            invoice.setCreditNoteTotal(invoice.getCreditNoteTotal().subtract(note.getTotalAmount()));
            BigDecimal balanceDue = invoice.getGrandTotal().subtract(invoice.getAmountReceived()).subtract(invoice.getCreditNoteTotal());
            invoice.setBalanceDue(balanceDue);
            invoice.setBalanceDueInr(scaleToInr(balanceDue, invoice.getFxRateToInr()));
            invoice.setStatus(InvoiceLifecyclePolicy.deriveFromBalance(invoice.getGrandTotal(), balanceDue));
            invoiceRepository.save(invoice);

            customerLedgerService.post(new LedgerPosting(
                    note.getClientId(), LocalDate.now(), LedgerEntryType.REVERSAL,
                    LedgerSourceType.CREDIT_NOTE, note.getId(), note.getCreditNoteNumber(),
                    "Credit note " + note.getCreditNoteNumber() + " cancelled: " + reason,
                    note.getBookingId(), note.getCurrencyCode(), note.getFxRateToInr(),
                    note.getTotalAmount(), BigDecimal.ZERO, note.getTotalAmountInr(), BigDecimal.ZERO));
            reverseCreditNoteIssuedJournal(note, reason);
        }

        note.setStatus(CreditNoteStatus.CANCELLED);
        note.setCancelledAt(now);
        note.setCancelledBy(actor);
        note.setCancelReason(reason);
        creditNoteRepository.save(note);

        auditService.recordUpdate(AuditEntityType.CREDIT_NOTE, note.getId(), note.getCreditNoteNumber(),
                List.of(AuditChange.builder().field("status").oldValue(beforeStatus).newValue(note.getStatus().name()).build()));
        log.info("Credit note cancelled: id={}", id);
        return toResponse(note);
    }

    @Transactional
    public CreditNoteResponse refund(String id, CreditNoteRefundRequest request) {
        return refund(id, request, false);
    }

    /**
     * {@code toWallet}: the refund stays with the agency as the client's account balance instead
     * of leaving the bank - the journal credits Client Advances Held rather than Bank, and the
     * caller adds the matching wallet deposit (PaymentReceiptService#creditWallet).
     */
    @Transactional
    public CreditNoteResponse refund(String id, CreditNoteRefundRequest request, boolean toWallet) {
        CreditNote note = findById(id);
        if (note.getStatus() != CreditNoteStatus.ISSUED) {
            throw new IllegalStateException("Only an issued credit note can be refunded");
        }

        BigDecimal alreadyRefunded = sumRefunded(note.getId());
        BigDecimal remaining = note.getRefundableAmount().subtract(alreadyRefunded);
        if (request.getAmount().compareTo(remaining) > 0) {
            throw new IllegalArgumentException("Refund amount exceeds the remaining refundable balance of " + remaining);
        }

        String number = documentNumberService.next(DocumentKind.RECEIPT, request.getReceivedOn());
        String actor = currentUserId();
        LocalDateTime now = LocalDateTime.now();

        PaymentReceipt receipt = PaymentReceipt.builder()
                .id(UniqueIdResolver.resolve(paymentReceiptRepository::existsById))
                .receiptNumber(number)
                .financialYear(FinancialYear.of(request.getReceivedOn()))
                .direction(ReceiptDirection.REFUND)
                .invoiceId(note.getInvoiceId())
                .creditNoteId(note.getId())
                .clientId(note.getClientId())
                .bookingId(note.getBookingId())
                .currencyCode(note.getCurrencyCode())
                .fxRateToInr(note.getFxRateToInr())
                .amount(request.getAmount())
                .amountInr(scaleToInr(request.getAmount(), note.getFxRateToInr()))
                .paymentMode(request.getPaymentMode())
                .instrumentRef(request.getInstrumentRef())
                .receivedOn(request.getReceivedOn())
                .isAdvance(false)
                .notes(request.getNotes())
                .createdAt(now)
                .createdBy(actor)
                .build();
        paymentReceiptRepository.save(receipt);

        customerLedgerService.post(new LedgerPosting(
                note.getClientId(), request.getReceivedOn(), LedgerEntryType.REFUND_PAID,
                LedgerSourceType.RECEIPT, receipt.getId(), receipt.getReceiptNumber(),
                "Refund " + receipt.getReceiptNumber() + " against " + note.getCreditNoteNumber(),
                note.getBookingId(), receipt.getCurrencyCode(), receipt.getFxRateToInr(),
                receipt.getAmountInr(), BigDecimal.ZERO, receipt.getAmountInr(), BigDecimal.ZERO));
        postRefundPaidJournal(note, receipt, toWallet);

        note.setRefundedAmount(alreadyRefunded.add(request.getAmount()));
        creditNoteRepository.save(note);

        // The refund sent cash back, so net cash kept on the invoice drops by that amount and the
        // negative balance the credit note left behind is cleared.
        invoiceRepository.findById(note.getInvoiceId()).ifPresent(invoice -> {
            invoice.setAmountReceived(invoice.getAmountReceived().subtract(request.getAmount()));
            BigDecimal balanceDue = invoice.getGrandTotal().subtract(invoice.getAmountReceived())
                    .subtract(invoice.getCreditNoteTotal());
            invoice.setBalanceDue(balanceDue);
            invoice.setBalanceDueInr(scaleToInr(balanceDue, invoice.getFxRateToInr()));
            invoice.setStatus(InvoiceLifecyclePolicy.deriveFromBalance(invoice.getGrandTotal(), balanceDue));
            invoiceRepository.save(invoice);
        });
        bookingAccountingSync.syncRefund(note.getBookingId());
        bookingAccountingSync.syncPayment(note.getBookingId());

        auditService.recordCreate(AuditEntityType.PAYMENT_RECEIPT, receipt.getId(), receipt.getReceiptNumber());
        log.info("Refund recorded: creditNoteId={}, receiptId={}, amount={}", note.getId(), receipt.getId(), request.getAmount());
        return toResponse(note);
    }

    @Transactional(readOnly = true)
    public CreditNoteResponse get(String id) {
        return toResponse(findAccessible(id));
    }

    @Transactional(readOnly = true)
    public byte[] getPdf(String id) {
        return CreditNotePdfRenderer.write(findAccessible(id));
    }

    @Transactional(readOnly = true)
    public Object list(String invoiceId, String clientId, Pageable pageable) {
        List<Specification<CreditNote>> predicates = new ArrayList<>();
        if (invoiceId != null) {
            predicates.add((root, query, cb) -> cb.equal(root.get("invoiceId"), invoiceId));
        }
        if (clientId != null) {
            predicates.add((root, query, cb) -> cb.equal(root.get("clientId"), clientId));
        }
        Specification<CreditNote> spec = Specification.allOf(predicates);

        if (pageable != null) {
            return PagedResponse.from(creditNoteRepository.findAll(spec, pageable), CreditNoteService::toResponse);
        }
        return creditNoteRepository.findAll(spec, Sort.by(Sort.Direction.DESC, "createdAt"))
                .stream().map(CreditNoteService::toResponse).toList();
    }

    /** What a cancellation credit note on this invoice would let the customer get back - the same
     *  maths {@link #create}/{@link #issue} use, without writing anything. */
    @Transactional(readOnly = true)
    public BigDecimal previewRefundable(Invoice invoice, BigDecimal cancellationFee, BigDecimal supplierRetained) {
        BigDecimal fee = cancellationFee.min(invoice.getTaxableValue()).max(BigDecimal.ZERO);
        return refundableFor(invoice, computeReversal(invoice, fee, supplierRetained).totalAmount, null);
    }

    // ---------------------------------------------------------------- internals

    private record Reversal(BigDecimal taxableValue, BigDecimal cgstAmount, BigDecimal sgstAmount,
                             BigDecimal igstAmount, BigDecimal tcsAmount, BigDecimal totalAmount) {
    }

    /**
     * Pro-rates the invoice's own persisted tax amounts by the ratio of taxable value being
     * credited to the invoice's total taxable value - there is no line-item table to apportion
     * per line, so this is the whole-invoice equivalent (see the class javadoc).
     */
    private Reversal computeReversal(Invoice invoice, BigDecimal cancellationFee, BigDecimal supplierRetained) {
        BigDecimal creditableTaxableValue = invoice.getTaxableValue().subtract(cancellationFee);
        BigDecimal ratio = invoice.getTaxableValue().compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : creditableTaxableValue.divide(invoice.getTaxableValue(), 10, RoundingMode.HALF_UP);
        BigDecimal feeRatio = commissionAgentFeeRatio(invoice, creditableTaxableValue, supplierRetained);
        if (feeRatio != null) {
            ratio = feeRatio;
        }

        BigDecimal cgst = invoice.getCgstAmount().multiply(ratio).setScale(2, RoundingMode.HALF_UP);
        BigDecimal sgst = invoice.getSgstAmount().multiply(ratio).setScale(2, RoundingMode.HALF_UP);
        BigDecimal igst = invoice.getIgstAmount().multiply(ratio).setScale(2, RoundingMode.HALF_UP);
        BigDecimal tcs = invoice.getTcsAmount().multiply(ratio).setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = creditableTaxableValue.add(cgst).add(sgst).add(igst).add(tcs);
        return new Reversal(creditableTaxableValue, cgst, sgst, igst, tcs, total);
    }

    /**
     * A commission-agent (air) invoice taxes only the agency's fee, so a cancellation gives back tax
     * only on the fee it refunds: the fare refunded is the pass-through less what the supplier kept,
     * and the rest of the credit is fee. Null when the invoice isn't one (pro-rate by taxable value).
     */
    private BigDecimal commissionAgentFeeRatio(Invoice invoice, BigDecimal creditableTaxableValue, BigDecimal supplierRetained) {
        if (supplierRetained == null || invoice.getBillingModel() != com.voyra.crm.enums.InvoiceBillingModel.COMMISSION_AGENT) {
            return null;
        }
        String passThroughCode = com.voyra.crm.enums.SystemAccount.CLIENT_PASS_THROUGH_PAYABLE.code();
        BigDecimal passThroughInr = journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(
                        com.voyra.crm.enums.JournalSourceType.INVOICE, invoice.getId(),
                        com.voyra.crm.enums.JournalPurpose.INVOICE_RAISED_COMMISSION_AGENT)
                .map(e -> journalLineRepository.findByJournalEntryIdOrderByLineNo(e.getId()).stream()
                        .filter(l -> passThroughCode.equals(l.getAccountCode()))
                        .map(l -> nz(l.getCreditAmountInr())).reduce(BigDecimal.ZERO, BigDecimal::add))
                .orElse(null);
        if (passThroughInr == null) {
            return null;
        }
        BigDecimal fx = invoice.getFxRateToInr() != null && invoice.getFxRateToInr().signum() > 0 ? invoice.getFxRateToInr() : BigDecimal.ONE;
        BigDecimal passThrough = passThroughInr.divide(fx, 2, RoundingMode.HALF_UP);
        BigDecimal feeBase = invoice.getTaxableValue().subtract(passThrough);
        if (feeBase.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal fareRefunded = passThrough.subtract(supplierRetained).max(BigDecimal.ZERO).min(creditableTaxableValue);
        BigDecimal feeRefunded = creditableTaxableValue.subtract(fareRefunded).max(BigDecimal.ZERO);
        return feeRefunded.divide(feeBase, 10, RoundingMode.HALF_UP).min(BigDecimal.ONE);
    }

    /** Caps total non-cancelled credit notes against one invoice at its own grand total. */
    private void assertWithinInvoiceValue(Invoice invoice, BigDecimal candidateAmount, String excludeId) {
        BigDecimal existing = creditNoteRepository.findByInvoiceIdAndStatus(invoice.getId(), CreditNoteStatus.ISSUED)
                .stream()
                .filter(cn -> !cn.getId().equals(excludeId))
                .map(CreditNote::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (existing.add(candidateAmount).compareTo(invoice.getGrandTotal()) > 0) {
            throw new IllegalStateException("This credit note would credit more than the invoice's grand total");
        }
    }

    /** Cash comes back only for what the customer paid beyond what is still billed once this
     *  credit lands - a partly paid invoice that is partly credited may owe nothing back. */
    private BigDecimal refundableFor(Invoice invoice, BigDecimal noteTotal, String excludeId) {
        BigDecimal otherCredits = creditNoteRepository.findByInvoiceIdAndStatus(invoice.getId(), CreditNoteStatus.ISSUED).stream()
                .filter(cn -> !cn.getId().equals(excludeId))
                .map(CreditNote::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal stillBilled = invoice.getGrandTotal().subtract(otherCredits).subtract(noteTotal);
        return cashReceiptedOnInvoice(invoice.getId()).subtract(stillBilled).max(BigDecimal.ZERO).min(noteTotal);
    }

    private BigDecimal sumRefunded(String creditNoteId) {
        return paymentReceiptRepository.findByCreditNoteIdOrderByReceivedOnAscCreatedAtAsc(creditNoteId).stream()
                .map(PaymentReceipt::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Net cash actually receipted against this invoice - RECEIPT-direction rows only (a
     *  reversal nets itself out, being the same direction with a negated amount), never the
     *  invoice's billed/grand total, which can include amounts never collected. */
    private BigDecimal cashReceiptedOnInvoice(String invoiceId) {
        return paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc(invoiceId).stream()
                .map(r -> r.getDirection() == ReceiptDirection.REFUND ? r.getAmount().negate() : r.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal scaleToInr(BigDecimal amount, BigDecimal fxRateToInr) {
        return amount.multiply(fxRateToInr).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Posting-rule-table row 12 - reverses revenue against whichever account the ORIGINAL
     * invoice actually credited (found via {@link com.voyra.crm.enums.JournalPurpose}, not
     * recomputed from today's departure date, since that can have moved on since issue). Only
     * {@code taxableValue} is reversed, never {@code cancellationFee} - Rule 1.5.1: the retained
     * fee stays in income, so there is nothing to post for it. A COMMISSION_AGENT invoice has no
     * posting-rule-table entry for its credit note yet - skipped with a log note rather than a
     * guess.
     */
    private void postCreditNoteIssuedJournal(CreditNote note, Invoice invoice) {
        BigDecimal taxableInr = note.getTaxableValue().multiply(note.getFxRateToInr()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal taxInr = note.getTotalAmountInr().subtract(taxableInr);
        if (taxInr.compareTo(BigDecimal.ZERO) < 0) {
            taxInr = BigDecimal.ZERO;
        }

        String revenueAccountCode = findOriginalRevenueAccountCode(invoice);
        if (revenueAccountCode == null && postCommissionAgentCreditNoteJournal(note, invoice)) {
            return;
        }
        if (revenueAccountCode == null) {
            log.warn("Credit note {} - could not find the original invoice's revenue posting (likely COMMISSION_AGENT, "
                    + "not yet covered by the posting-rule table) - skipping GL entry", note.getId());
            return;
        }

        List<com.voyra.crm.models.JournalLinePosting> lines = new ArrayList<>();
        if (taxableInr.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(com.voyra.crm.models.JournalLinePosting.debit(revenueAccountCode, taxableInr, "Revenue reversed"));
        }
        if (taxInr.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(com.voyra.crm.models.JournalLinePosting.debit(
                    com.voyra.crm.enums.SystemAccount.OUTPUT_GST_PAYABLE.code(), taxInr, "GST reversed"));
        }
        if (lines.isEmpty()) {
            return;
        }
        lines.add(com.voyra.crm.models.JournalLinePosting.creditParty(
                com.voyra.crm.enums.SystemAccount.ACCOUNTS_RECEIVABLE.code(), "CLIENT", note.getClientId(),
                taxableInr.add(taxInr), "Credit note " + note.getCreditNoteNumber()));

        journalService.post(new com.voyra.crm.models.JournalPosting(
                note.getNoteDate() != null ? note.getNoteDate() : LocalDate.now(),
                com.voyra.crm.enums.JournalSourceType.CREDIT_NOTE, note.getId(),
                com.voyra.crm.enums.JournalPurpose.CREDIT_NOTE_ISSUED,
                "Credit note " + note.getCreditNoteNumber() + " against " + invoice.getInvoiceNumber(),
                note.getBookingId(), note.getBranchId(), lines));
    }

    /**
     * A COMMISSION_AGENT invoice credited pass-through payable, service-fee income and GST rather
     * than one revenue account, so its credit note reverses those same lines in the proportion
     * the note bears to the invoice (a full refund reverses them in full), against AR. Returns
     * false when the invoice posted no commission-agent journal (nothing to mirror).
     */
    private boolean postCommissionAgentCreditNoteJournal(CreditNote note, Invoice invoice) {
        var original = journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(
                com.voyra.crm.enums.JournalSourceType.INVOICE, invoice.getId(),
                com.voyra.crm.enums.JournalPurpose.INVOICE_RAISED_COMMISSION_AGENT);
        if (original.isEmpty() || invoice.getGrandTotalInr() == null || invoice.getGrandTotalInr().signum() == 0) {
            return false;
        }
        String receivable = com.voyra.crm.enums.SystemAccount.ACCOUNTS_RECEIVABLE.code();
        String passThrough = com.voyra.crm.enums.SystemAccount.CLIENT_PASS_THROUGH_PAYABLE.code();
        List<com.voyra.crm.entity.JournalLine> originalLines = journalLineRepository.findByJournalEntryIdOrderByLineNo(original.get().getId())
                .stream().filter(l -> !receivable.equals(l.getAccountCode())).toList();
        java.util.Map<String, BigDecimal> amounts = new java.util.LinkedHashMap<>();
        if (note.getSupplierRetained() != null) {
            // A cancellation: the supplier's share of what was kept stays as pass-through (the client
            // still owes it to the supplier); the rest of the refund comes off the agency's own lines
            // (fee, then its GST) in proportion - so the agency fee kept is the only income left.
            BigDecimal passThroughCredited = originalLines.stream().filter(l -> passThrough.equals(l.getAccountCode()))
                    .map(l -> nz(l.getCreditAmountInr())).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal refund = note.getTotalAmountInr();
            BigDecimal fromPassThrough = passThroughCredited.subtract(note.getSupplierRetained()).max(BigDecimal.ZERO).min(refund);
            if (fromPassThrough.signum() > 0) amounts.put(passThrough, fromPassThrough);
            BigDecimal rest = refund.subtract(fromPassThrough);
            BigDecimal agencyCredited = originalLines.stream().filter(l -> !passThrough.equals(l.getAccountCode()))
                    .map(l -> nz(l.getCreditAmountInr())).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (rest.signum() > 0 && agencyCredited.signum() > 0) {
                BigDecimal allocated = BigDecimal.ZERO;
                List<com.voyra.crm.entity.JournalLine> agencyLines = originalLines.stream()
                        .filter(l -> !passThrough.equals(l.getAccountCode()) && nz(l.getCreditAmountInr()).signum() > 0).toList();
                for (int i = 0; i < agencyLines.size(); i++) {
                    com.voyra.crm.entity.JournalLine l = agencyLines.get(i);
                    BigDecimal share = i == agencyLines.size() - 1 ? rest.subtract(allocated)
                            : rest.multiply(nz(l.getCreditAmountInr())).divide(agencyCredited, 2, RoundingMode.HALF_UP);
                    amounts.merge(l.getAccountCode(), share, BigDecimal::add);
                    allocated = allocated.add(share);
                }
            }
        } else {
            BigDecimal ratio = note.getTotalAmountInr().divide(invoice.getGrandTotalInr(), 10, RoundingMode.HALF_UP).min(BigDecimal.ONE);
            for (com.voyra.crm.entity.JournalLine line : originalLines) {
                BigDecimal amount = nz(line.getCreditAmountInr()).multiply(ratio).setScale(2, RoundingMode.HALF_UP);
                if (amount.signum() > 0) amounts.merge(line.getAccountCode(), amount, BigDecimal::add);
            }
        }
        List<com.voyra.crm.models.JournalLinePosting> lines = new ArrayList<>();
        BigDecimal reversed = BigDecimal.ZERO;
        for (var e : amounts.entrySet()) {
            if (e.getValue().signum() > 0) {
                lines.add(com.voyra.crm.models.JournalLinePosting.debit(e.getKey(), e.getValue(), "Reversed on credit note"));
                reversed = reversed.add(e.getValue());
            }
        }
        if (lines.isEmpty()) {
            return true;
        }
        lines.add(com.voyra.crm.models.JournalLinePosting.creditParty(receivable, "CLIENT", note.getClientId(), reversed,
                "Credit note " + note.getCreditNoteNumber()));
        journalService.post(new com.voyra.crm.models.JournalPosting(
                note.getNoteDate() != null ? note.getNoteDate() : LocalDate.now(),
                com.voyra.crm.enums.JournalSourceType.CREDIT_NOTE, note.getId(),
                com.voyra.crm.enums.JournalPurpose.CREDIT_NOTE_ISSUED,
                "Credit note " + note.getCreditNoteNumber() + " against " + invoice.getInvoiceNumber(),
                note.getBookingId(), note.getBranchId(), lines));
        return true;
    }

    /** The account code the original invoice's issue journal credited for revenue - null if none of the three purposes posted (or posting predates the GL). */
    private String findOriginalRevenueAccountCode(Invoice invoice) {
        for (com.voyra.crm.enums.JournalPurpose purpose : List.of(
                com.voyra.crm.enums.JournalPurpose.INVOICE_RAISED_DEFERRED,
                com.voyra.crm.enums.JournalPurpose.INVOICE_RAISED_RECOGNIZED)) {
            var entry = journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(
                    com.voyra.crm.enums.JournalSourceType.INVOICE, invoice.getId(), purpose);
            if (entry.isPresent()) {
                return purpose == com.voyra.crm.enums.JournalPurpose.INVOICE_RAISED_DEFERRED
                        ? com.voyra.crm.enums.SystemAccount.UNEARNED_TOUR_REVENUE.code()
                        : com.voyra.crm.enums.SystemAccount.salesCode(
                                invoice.getServiceCategory() != null ? invoice.getServiceCategory() : com.voyra.crm.enums.InvoiceServiceCategory.MISCELLANEOUS);
            }
        }
        return null;
    }

    private void reverseCreditNoteIssuedJournal(CreditNote note, String reason) {
        journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(
                com.voyra.crm.enums.JournalSourceType.CREDIT_NOTE, note.getId(), com.voyra.crm.enums.JournalPurpose.CREDIT_NOTE_ISSUED)
                .ifPresent(entry -> journalService.reverse(entry.getId(),
                        "Credit note " + note.getCreditNoteNumber() + " cancelled: " + reason));
    }

    /** Posting-rule-table row 13 - a cash refund paid out against a credit note. */
    private void postRefundPaidJournal(CreditNote note, PaymentReceipt receipt, boolean toWallet) {
        BigDecimal amountInr = receipt.getAmountInr();
        if (amountInr == null || amountInr.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        String bankCode = receipt.getPaymentMode() == com.voyra.crm.enums.PaymentMode.CASH
                ? com.voyra.crm.enums.SystemAccount.CASH_IN_HAND.code()
                : com.voyra.crm.enums.SystemAccount.BANK_ACCOUNTS.code();
        String narration = "Refund " + receipt.getReceiptNumber() + " against " + note.getCreditNoteNumber()
                + (toWallet ? ", credited to client account" : "");
        com.voyra.crm.models.JournalLinePosting creditLine = toWallet
                ? com.voyra.crm.models.JournalLinePosting.creditParty(
                        com.voyra.crm.enums.SystemAccount.CLIENT_ADVANCES_HELD.code(), "CLIENT", note.getClientId(), amountInr, narration)
                : com.voyra.crm.models.JournalLinePosting.credit(bankCode, amountInr, narration);
        journalService.post(new com.voyra.crm.models.JournalPosting(
                receipt.getReceivedOn(), com.voyra.crm.enums.JournalSourceType.RECEIPT, receipt.getId(),
                com.voyra.crm.enums.JournalPurpose.CLIENT_REFUND_PAID, narration, note.getBookingId(), note.getBranchId(), List.of(
                        com.voyra.crm.models.JournalLinePosting.debitParty(
                                com.voyra.crm.enums.SystemAccount.ACCOUNTS_RECEIVABLE.code(), "CLIENT", note.getClientId(), amountInr, narration),
                        creditLine)));
    }

    private CreditNote findById(String id) {
        return creditNoteRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Credit note not found: " + id));
    }

    private CreditNote findAccessible(String id) {
        CreditNote note = findById(id);
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        if (principal.isAgent()) {
            throw new AccessDeniedException("Credit notes are not accessible to agents");
        }
        return note;
    }

    private String currentUserId() {
        return SecurityContextUtil.getCurrentUserOrThrow().userId();
    }

    private static CreditNoteResponse toResponse(CreditNote n) {
        return CreditNoteResponse.builder()
                .id(n.getId()).creditNoteNumber(n.getCreditNoteNumber()).financialYear(n.getFinancialYear())
                .invoiceId(n.getInvoiceId()).invoiceNumber(n.getInvoiceNumber())
                .clientId(n.getClientId()).clientName(n.getClientName()).bookingId(n.getBookingId())
                .reason(n.getReason()).reasonNote(n.getReasonNote()).status(n.getStatus())
                .currencyCode(n.getCurrencyCode()).fxRateToInr(n.getFxRateToInr())
                .taxableValue(n.getTaxableValue()).cgstAmount(n.getCgstAmount()).sgstAmount(n.getSgstAmount())
                .igstAmount(n.getIgstAmount()).tcsAmount(n.getTcsAmount()).cancellationFee(n.getCancellationFee())
                .totalAmount(n.getTotalAmount()).totalAmountInr(n.getTotalAmountInr())
                .refundableAmount(n.getRefundableAmount()).refundedAmount(n.getRefundedAmount())
                .noteDate(n.getNoteDate()).issuedAt(n.getIssuedAt()).issuedBy(n.getIssuedBy())
                .cancelledAt(n.getCancelledAt()).cancelledBy(n.getCancelledBy()).cancelReason(n.getCancelReason())
                .build();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
