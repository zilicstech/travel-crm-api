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
 * posts a {@code REFUND_PAID} debit: it settles the client's credit balance in cash but never
 * touches the invoice's own {@code balanceDue}, since that money was never billed on the invoice
 * to begin with. This means {@code CustomerLedgerService}'s documented invariant needs one more
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

        Reversal reversal = computeReversal(invoice, cancellationFee);
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
        BigDecimal cashReceipted = cashReceiptedOnInvoice(invoice.getId());
        note.setRefundableAmount(note.getTotalAmount().min(cashReceipted).max(BigDecimal.ZERO));
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

        note.setRefundedAmount(alreadyRefunded.add(request.getAmount()));
        creditNoteRepository.save(note);
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

    // ---------------------------------------------------------------- internals

    private record Reversal(BigDecimal taxableValue, BigDecimal cgstAmount, BigDecimal sgstAmount,
                             BigDecimal igstAmount, BigDecimal tcsAmount, BigDecimal totalAmount) {
    }

    /**
     * Pro-rates the invoice's own persisted tax amounts by the ratio of taxable value being
     * credited to the invoice's total taxable value - there is no line-item table to apportion
     * per line, so this is the whole-invoice equivalent (see the class javadoc).
     */
    private Reversal computeReversal(Invoice invoice, BigDecimal cancellationFee) {
        BigDecimal creditableTaxableValue = invoice.getTaxableValue().subtract(cancellationFee);
        BigDecimal ratio = invoice.getTaxableValue().compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : creditableTaxableValue.divide(invoice.getTaxableValue(), 10, RoundingMode.HALF_UP);

        BigDecimal cgst = invoice.getCgstAmount().multiply(ratio).setScale(2, RoundingMode.HALF_UP);
        BigDecimal sgst = invoice.getSgstAmount().multiply(ratio).setScale(2, RoundingMode.HALF_UP);
        BigDecimal igst = invoice.getIgstAmount().multiply(ratio).setScale(2, RoundingMode.HALF_UP);
        BigDecimal tcs = invoice.getTcsAmount().multiply(ratio).setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = creditableTaxableValue.add(cgst).add(sgst).add(igst).add(tcs);
        return new Reversal(creditableTaxableValue, cgst, sgst, igst, tcs, total);
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
                .filter(r -> r.getDirection() == ReceiptDirection.RECEIPT)
                .map(PaymentReceipt::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal scaleToInr(BigDecimal amount, BigDecimal fxRateToInr) {
        return amount.multiply(fxRateToInr).setScale(2, RoundingMode.HALF_UP);
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
}
