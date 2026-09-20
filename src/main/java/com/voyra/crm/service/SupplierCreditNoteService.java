package com.voyra.crm.service;

import com.voyra.crm.dto.SupplierCreditNoteRefundRequest;
import com.voyra.crm.dto.SupplierCreditNoteRequest;
import com.voyra.crm.dto.SupplierCreditNoteResponse;
import com.voyra.crm.entity.SupplierCreditNote;
import com.voyra.crm.entity.SupplierInvoice;
import com.voyra.crm.entity.SupplierPayment;
import com.voyra.crm.enums.AuditEntityType;
import com.voyra.crm.enums.SupplierCreditNoteStatus;
import com.voyra.crm.enums.SupplierInvoiceStatus;
import com.voyra.crm.enums.SupplierLedgerEntryType;
import com.voyra.crm.enums.SupplierLedgerSourceType;
import com.voyra.crm.enums.SupplierPaymentDirection;
import com.voyra.crm.models.SupplierLedgerPosting;
import com.voyra.crm.repository.SupplierCreditNoteRepository;
import com.voyra.crm.repository.SupplierInvoiceRepository;
import com.voyra.crm.repository.SupplierPaymentRepository;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.SupplierInvoiceLifecyclePolicy;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A document the SUPPLIER issues to us - cancellation refund or a rate correction against one
 * approved bill. Like a supplier bill, the tax figures are RECORDED from the supplier's own note,
 * never computed (AD-2) - there is no pro-rata reversal math here the way
 * {@code CreditNoteService} has on the AR side, because the accountant enters the already-net
 * (post-retention) figures the supplier's own note shows.
 *
 * <p>Recording posts a {@code CREDIT_NOTE_RECEIVED} debit (lowers payable, AD-4) and raises the
 * bill's {@code creditNoteTotal} permanently, even after a refund. A refund actually received back
 * posts a separate {@code REFUND_RECEIVED} credit and never touches the bill's own
 * {@code balanceDue} - mirrors {@code CreditNoteService}'s refund/balanceDue split exactly.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SupplierCreditNoteService {

    private final SupplierCreditNoteRepository supplierCreditNoteRepository;
    private final SupplierInvoiceRepository supplierInvoiceRepository;
    private final SupplierPaymentRepository supplierPaymentRepository;
    private final DocumentNumberService documentNumberService;
    private final SupplierLedgerService supplierLedgerService;
    private final AuditService auditService;

    @Transactional
    public SupplierCreditNoteResponse record(SupplierCreditNoteRequest request) {
        SupplierInvoice invoice = findInvoice(request.getSupplierInvoiceId());
        if (invoice.getStatus() != SupplierInvoiceStatus.APPROVED && invoice.getStatus() != SupplierInvoiceStatus.PARTIALLY_PAID
                && invoice.getStatus() != SupplierInvoiceStatus.PAID) {
            throw new IllegalStateException("Only an approved bill can carry a credit note");
        }

        BigDecimal retentionFee = request.getRetentionFee() != null ? request.getRetentionFee() : BigDecimal.ZERO;
        BigDecimal totalAmount = request.getTaxableValue().add(request.getCgstAmount())
                .add(request.getSgstAmount()).add(request.getIgstAmount());
        assertWithinInvoiceValue(invoice, totalAmount, null);

        LocalDate noteDate = request.getNoteDate() != null ? request.getNoteDate() : LocalDate.now();
        String actor = currentUserId();
        LocalDateTime now = LocalDateTime.now();

        SupplierCreditNote note = SupplierCreditNote.builder()
                .id(UniqueIdResolver.resolve(supplierCreditNoteRepository::existsById))
                .supplierNoteNumber(request.getSupplierNoteNumber())
                .vendorId(invoice.getVendorId())
                .vendorName(invoice.getVendorName())
                .supplierInvoiceId(invoice.getId())
                .supplierInvoiceNumber(invoice.getSupplierInvoiceNumber())
                .bookingId(invoice.getBookingId())
                .reason(request.getReason())
                .reasonNote(request.getReasonNote())
                .status(SupplierCreditNoteStatus.RECORDED)
                .currencyCode(invoice.getCurrencyCode())
                .fxRateToInr(invoice.getFxRateToInr())
                .taxableValue(request.getTaxableValue())
                .cgstAmount(request.getCgstAmount()).sgstAmount(request.getSgstAmount()).igstAmount(request.getIgstAmount())
                .retentionFee(retentionFee)
                .totalAmount(totalAmount)
                .totalAmountInr(scaleToInr(totalAmount, invoice.getFxRateToInr()))
                .refundableAmount(totalAmount)
                .noteDate(noteDate)
                .recordedAt(now)
                .recordedBy(actor)
                .createdAt(now).createdBy(actor)
                .build();
        supplierCreditNoteRepository.save(note);

        invoice.setCreditNoteTotal(invoice.getCreditNoteTotal().add(totalAmount));
        BigDecimal balanceDue = invoice.getGrandTotal().subtract(invoice.getAmountPaid()).subtract(invoice.getCreditNoteTotal());
        invoice.setBalanceDue(balanceDue);
        invoice.setBalanceDueInr(scaleToInr(balanceDue, invoice.getFxRateToInr()));
        invoice.setStatus(SupplierInvoiceLifecyclePolicy.deriveFromBalance(invoice.getGrandTotal(), balanceDue));
        supplierInvoiceRepository.save(invoice);

        supplierLedgerService.post(new SupplierLedgerPosting(
                note.getVendorId(), noteDate, SupplierLedgerEntryType.CREDIT_NOTE_RECEIVED,
                SupplierLedgerSourceType.SUPPLIER_CREDIT_NOTE, note.getId(), note.getSupplierNoteNumber(),
                "Supplier credit note " + labelFor(note) + " against " + invoice.getSupplierInvoiceNumber(),
                note.getBookingId(), note.getCurrencyCode(), note.getFxRateToInr(),
                note.getTotalAmount(), BigDecimal.ZERO, note.getTotalAmountInr(), BigDecimal.ZERO));

        auditService.recordCreate(AuditEntityType.SUPPLIER_CREDIT_NOTE, note.getId(), labelFor(note));
        log.info("Supplier credit note recorded: id={}, supplierInvoiceId={}, totalAmount={}", note.getId(), invoice.getId(), totalAmount);
        return toResponse(note);
    }

    @Transactional
    public SupplierCreditNoteResponse cancel(String id, String reason) {
        SupplierCreditNote note = findById(id);
        if (note.getStatus() == SupplierCreditNoteStatus.CANCELLED) {
            throw new IllegalStateException("This credit note is already cancelled");
        }
        if (note.getRefundedAmount().compareTo(BigDecimal.ZERO) > 0) {
            throw new IllegalStateException("This credit note has refunds against it and can no longer be cancelled");
        }

        SupplierInvoice invoice = findInvoice(note.getSupplierInvoiceId());
        invoice.setCreditNoteTotal(invoice.getCreditNoteTotal().subtract(note.getTotalAmount()));
        BigDecimal balanceDue = invoice.getGrandTotal().subtract(invoice.getAmountPaid()).subtract(invoice.getCreditNoteTotal());
        invoice.setBalanceDue(balanceDue);
        invoice.setBalanceDueInr(scaleToInr(balanceDue, invoice.getFxRateToInr()));
        invoice.setStatus(SupplierInvoiceLifecyclePolicy.deriveFromBalance(invoice.getGrandTotal(), balanceDue));
        supplierInvoiceRepository.save(invoice);

        supplierLedgerService.post(new SupplierLedgerPosting(
                note.getVendorId(), LocalDate.now(), SupplierLedgerEntryType.REVERSAL,
                SupplierLedgerSourceType.SUPPLIER_CREDIT_NOTE, note.getId(), note.getSupplierNoteNumber(),
                "Supplier credit note " + labelFor(note) + " cancelled: " + reason,
                note.getBookingId(), note.getCurrencyCode(), note.getFxRateToInr(),
                BigDecimal.ZERO, note.getTotalAmount(), BigDecimal.ZERO, note.getTotalAmountInr()));

        note.setStatus(SupplierCreditNoteStatus.CANCELLED);
        note.setCancelledAt(LocalDateTime.now());
        note.setCancelledBy(currentUserId());
        note.setCancelReason(reason);
        supplierCreditNoteRepository.save(note);

        log.info("Supplier credit note cancelled: id={}", id);
        return toResponse(note);
    }

    @Transactional
    public SupplierCreditNoteResponse receiveRefund(String id, SupplierCreditNoteRefundRequest request) {
        SupplierCreditNote note = findById(id);
        if (note.getStatus() != SupplierCreditNoteStatus.RECORDED) {
            throw new IllegalStateException("Only a recorded credit note can be refunded");
        }
        BigDecimal remaining = note.getRefundableAmount().subtract(note.getRefundedAmount());
        if (request.getAmount().compareTo(remaining) > 0) {
            throw new IllegalArgumentException("Refund amount exceeds the remaining refundable balance of " + remaining);
        }

        String actor = currentUserId();
        LocalDateTime now = LocalDateTime.now();

        SupplierPayment payment = SupplierPayment.builder()
                .id(UniqueIdResolver.resolve(supplierPaymentRepository::existsById))
                .direction(SupplierPaymentDirection.REFUND_RECEIVED)
                .vendorId(note.getVendorId()).vendorName(note.getVendorName())
                .supplierInvoiceId(note.getSupplierInvoiceId()).supplierCreditNoteId(note.getId())
                .bookingId(note.getBookingId())
                .currencyCode(note.getCurrencyCode()).fxRateToInr(note.getFxRateToInr())
                .amount(request.getAmount()).amountInr(scaleToInr(request.getAmount(), note.getFxRateToInr()))
                .tdsWithheld(BigDecimal.ZERO)
                .paymentMode(request.getPaymentMode()).instrumentRef(request.getInstrumentRef())
                .paidOn(request.getReceivedOn())
                .isAdvance(false).appliedFromAdvance(false)
                .notes(request.getNotes())
                .createdAt(now).createdBy(actor)
                .build();
        supplierPaymentRepository.save(payment);

        supplierLedgerService.post(new SupplierLedgerPosting(
                note.getVendorId(), request.getReceivedOn(), SupplierLedgerEntryType.REFUND_RECEIVED,
                SupplierLedgerSourceType.SUPPLIER_PAYMENT, payment.getId(), null,
                "Refund received against supplier credit note " + labelFor(note),
                note.getBookingId(), payment.getCurrencyCode(), payment.getFxRateToInr(),
                BigDecimal.ZERO, payment.getAmountInr(), BigDecimal.ZERO, payment.getAmountInr()));

        note.setRefundedAmount(note.getRefundedAmount().add(request.getAmount()));
        supplierCreditNoteRepository.save(note);

        auditService.recordCreate(AuditEntityType.SUPPLIER_PAYMENT, payment.getId(), "Refund against " + labelFor(note));
        log.info("Supplier refund received: creditNoteId={}, amount={}", note.getId(), request.getAmount());
        return toResponse(note);
    }

    @Transactional(readOnly = true)
    public SupplierCreditNoteResponse get(String id) {
        return toResponse(findById(id));
    }

    @Transactional(readOnly = true)
    public List<SupplierCreditNoteResponse> listForVendor(String vendorId) {
        return supplierCreditNoteRepository.findByVendorIdOrderByNoteDateDesc(vendorId).stream()
                .map(SupplierCreditNoteService::toResponse).toList();
    }

    // ---------------------------------------------------------------- internals

    /** Caps total non-cancelled supplier credit notes against one bill at its own grand total. */
    private void assertWithinInvoiceValue(SupplierInvoice invoice, BigDecimal candidateAmount, String excludeId) {
        BigDecimal existing = supplierCreditNoteRepository.findBySupplierInvoiceIdAndStatus(invoice.getId(), SupplierCreditNoteStatus.RECORDED)
                .stream()
                .filter(cn -> !cn.getId().equals(excludeId))
                .map(SupplierCreditNote::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (existing.add(candidateAmount).compareTo(invoice.getGrandTotal()) > 0) {
            throw new IllegalStateException("This credit note would credit more than the bill's grand total");
        }
    }

    private SupplierInvoice findInvoice(String id) {
        return supplierInvoiceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Supplier bill not found: " + id));
    }

    private SupplierCreditNote findById(String id) {
        return supplierCreditNoteRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Supplier credit note not found: " + id));
    }

    private static BigDecimal scaleToInr(BigDecimal amount, BigDecimal fxRateToInr) {
        return amount.multiply(fxRateToInr).setScale(2, RoundingMode.HALF_UP);
    }

    private String currentUserId() {
        return SecurityContextUtil.getCurrentUserOrThrow().userId();
    }

    private static String labelFor(SupplierCreditNote n) {
        return n.getSupplierNoteNumber() != null ? n.getSupplierNoteNumber() : n.getId();
    }

    private static SupplierCreditNoteResponse toResponse(SupplierCreditNote n) {
        return SupplierCreditNoteResponse.builder()
                .id(n.getId()).supplierNoteNumber(n.getSupplierNoteNumber())
                .vendorId(n.getVendorId()).vendorName(n.getVendorName())
                .supplierInvoiceId(n.getSupplierInvoiceId()).supplierInvoiceNumber(n.getSupplierInvoiceNumber())
                .bookingId(n.getBookingId()).reason(n.getReason()).reasonNote(n.getReasonNote()).status(n.getStatus())
                .currencyCode(n.getCurrencyCode()).fxRateToInr(n.getFxRateToInr())
                .taxableValue(n.getTaxableValue()).cgstAmount(n.getCgstAmount()).sgstAmount(n.getSgstAmount())
                .igstAmount(n.getIgstAmount()).retentionFee(n.getRetentionFee())
                .totalAmount(n.getTotalAmount()).totalAmountInr(n.getTotalAmountInr())
                .refundableAmount(n.getRefundableAmount()).refundedAmount(n.getRefundedAmount())
                .noteDate(n.getNoteDate()).recordedAt(n.getRecordedAt()).recordedBy(n.getRecordedBy())
                .cancelledAt(n.getCancelledAt()).cancelledBy(n.getCancelledBy()).cancelReason(n.getCancelReason())
                .build();
    }
}
