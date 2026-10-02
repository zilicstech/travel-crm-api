package com.voyra.crm.service;

import com.voyra.crm.dto.SupplierAdvanceApplyRequest;
import com.voyra.crm.dto.SupplierAdvanceRowResponse;
import com.voyra.crm.dto.SupplierPaymentRequest;
import com.voyra.crm.dto.SupplierPaymentResponse;
import com.voyra.crm.dto.SupplierUnallocatedAdvancesResponse;
import com.voyra.crm.entity.SupplierInvoice;
import com.voyra.crm.entity.SupplierPayment;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.entity.Vendor;
import com.voyra.crm.enums.AuditEntityType;
import com.voyra.crm.enums.DocumentKind;
import com.voyra.crm.enums.PaymentMode;
import com.voyra.crm.enums.SupplierInvoiceStatus;
import com.voyra.crm.enums.SupplierLedgerEntryType;
import com.voyra.crm.enums.SupplierLedgerSourceType;
import com.voyra.crm.enums.SupplierPaymentDirection;
import com.voyra.crm.models.SupplierLedgerPosting;
import com.voyra.crm.repository.SupplierInvoiceRepository;
import com.voyra.crm.repository.SupplierPaymentRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.repository.VendorRepository;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.FinancialYear;
import com.voyra.crm.util.PaymentAdvicePdfRenderer;
import com.voyra.crm.util.SupplierInvoiceLifecyclePolicy;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Records and reverses {@link SupplierPayment} rows - the accounts-payable mirror of
 * {@code PaymentReceiptService}. Three shapes share this table: a payment against a bill
 * ({@code supplierInvoiceId} set), a pure advance/deposit ({@code isAdvance = true}, no invoice),
 * and applying an existing advance to a bill ({@code appliedFromAdvance = true}) - the third posts
 * no ledger row at all, because the advance already posted its debit and the bill already posted
 * its credit when each was created (ARCHITECTURE-SPINE AD-5). Never writes to
 * {@code booking.netCost}/{@code profit} (AD-9).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SupplierPaymentService {

    private final SupplierPaymentRepository supplierPaymentRepository;
    private final SupplierInvoiceRepository supplierInvoiceRepository;
    private final VendorRepository vendorRepository;
    private final TenantRepository tenantRepository;
    private final DocumentNumberService documentNumberService;
    private final SupplierLedgerService supplierLedgerService;
    private final AuditService auditService;
    private final JournalService journalService;
    private final com.voyra.crm.repository.JournalEntryRepository journalEntryRepository;

    @Transactional
    public SupplierPaymentResponse pay(SupplierPaymentRequest request) {
        Vendor vendor = findVendor(request.getVendorId());
        SupplierInvoice invoice = null;
        boolean isAdvance = request.getSupplierInvoiceId() == null || request.getSupplierInvoiceId().isBlank();
        if (!isAdvance) {
            invoice = findInvoice(request.getSupplierInvoiceId());
            if (!vendor.getId().equals(invoice.getVendorId())) {
                throw new IllegalArgumentException("This bill does not belong to the selected vendor");
            }
            SupplierInvoiceLifecyclePolicy.assertPayable(invoice.getStatus());
        }

        LocalDate paidOn = request.getPaidOn();
        String number = documentNumberService.next(DocumentKind.PAYMENT_VOUCHER, paidOn);
        String actor = currentUserId();

        SupplierPayment payment = SupplierPayment.builder()
                .id(UniqueIdResolver.resolve(supplierPaymentRepository::existsById))
                .voucherNumber(number)
                .financialYear(FinancialYear.of(paidOn))
                .direction(SupplierPaymentDirection.PAYMENT)
                .vendorId(vendor.getId())
                .vendorName(vendor.getName())
                .supplierInvoiceId(invoice != null ? invoice.getId() : null)
                .bookingId(invoice != null ? invoice.getBookingId() : null)
                .currencyCode(invoice != null ? invoice.getCurrencyCode() : "INR")
                .fxRateToInr(invoice != null ? invoice.getFxRateToInr() : BigDecimal.ONE)
                .amount(request.getAmount())
                .amountInr(scaleToInr(request.getAmount(), invoice != null ? invoice.getFxRateToInr() : BigDecimal.ONE))
                .tdsWithheld(request.getTdsWithheld() != null ? request.getTdsWithheld() : BigDecimal.ZERO)
                .paymentMode(request.getPaymentMode())
                .instrumentRef(request.getInstrumentRef())
                .bankAccountLabel(request.getBankAccountLabel())
                .paidOn(paidOn)
                .isAdvance(isAdvance)
                .appliedFromAdvance(false)
                .notes(request.getNotes())
                .createdAt(LocalDateTime.now())
                .createdBy(actor)
                .build();
        supplierPaymentRepository.save(payment);
        postForPayment(payment);
        postPaymentJournal(payment, invoice);

        if (invoice != null) {
            applySettlement(invoice, payment.getAmount());
        }

        auditService.recordCreate(AuditEntityType.SUPPLIER_PAYMENT, payment.getId(), payment.getVoucherNumber());
        log.info("Supplier payment recorded: id={}, vendorId={}, isAdvance={}, amount={}",
                payment.getId(), vendor.getId(), isAdvance, payment.getAmount());
        return toResponse(payment);
    }

    /**
     * Moves part or all of an existing advance onto a bill. No ledger row - see class javadoc
     * and AD-5. The remaining advance pool for a vendor is the sum of their advance payments
     * minus the sum of what has already been applied from them; a specific advance row is not
     * individually tracked once paid in, since the ledger already nets the vendor's whole
     * position regardless of which advance funded which bill.
     */
    @Transactional
    public SupplierPaymentResponse applyAdvance(String supplierInvoiceId, SupplierAdvanceApplyRequest request) {
        SupplierInvoice invoice = findInvoice(supplierInvoiceId);
        SupplierInvoiceLifecyclePolicy.assertPayable(invoice.getStatus());

        SupplierPayment advance = supplierPaymentRepository.findById(request.getAdvancePaymentId())
                .orElseThrow(() -> new IllegalArgumentException("Advance payment not found: " + request.getAdvancePaymentId()));
        if (!Boolean.TRUE.equals(advance.getIsAdvance()) || advance.getReversedAt() != null) {
            throw new IllegalArgumentException("This payment is not an active advance");
        }
        if (!advance.getVendorId().equals(invoice.getVendorId())) {
            throw new IllegalArgumentException("This advance belongs to a different vendor");
        }

        BigDecimal remaining = remainingAdvancePool(advance.getVendorId());
        if (request.getAmount().compareTo(remaining) > 0) {
            throw new IllegalStateException("Only " + remaining + " of advance remains unapplied for this vendor");
        }

        SupplierPayment applied = SupplierPayment.builder()
                .id(UniqueIdResolver.resolve(supplierPaymentRepository::existsById))
                .direction(SupplierPaymentDirection.PAYMENT)
                .vendorId(advance.getVendorId())
                .vendorName(advance.getVendorName())
                .supplierInvoiceId(invoice.getId())
                .bookingId(invoice.getBookingId())
                .currencyCode(invoice.getCurrencyCode())
                .fxRateToInr(invoice.getFxRateToInr())
                .amount(request.getAmount())
                .amountInr(scaleToInr(request.getAmount(), invoice.getFxRateToInr()))
                .tdsWithheld(BigDecimal.ZERO)
                .paymentMode(PaymentMode.ADJUSTMENT)
                .paidOn(LocalDate.now())
                .isAdvance(false)
                .appliedFromAdvance(true)
                .notes("Applied from advance " + advance.getId())
                .createdAt(LocalDateTime.now())
                .createdBy(currentUserId())
                .build();
        supplierPaymentRepository.save(applied);
        // No SUBSIDIARY ledger post - AD-5. Row 11 still needs a GL entry: moving a balance
        // from 1300 to 2200 is a real double-entry transfer, the mirror of the customer-side
        // ADVANCE_APPLIED (PaymentReceiptService#postAdvanceAppliedJournal).
        postAdvanceAppliedJournal(applied, invoice);

        applySettlement(invoice, applied.getAmount());

        auditService.recordCreate(AuditEntityType.SUPPLIER_PAYMENT, applied.getId(), "Advance applied to " + invoice.getVendorName());
        log.info("Advance applied: advanceId={}, supplierInvoiceId={}, amount={}", advance.getId(), supplierInvoiceId, applied.getAmount());
        return toResponse(applied);
    }

    /** Writes a new, opposite-signed payment and stamps the original as reversed - the original's own amount is never edited. */
    @Transactional
    public SupplierPaymentResponse reverse(String id, String reason) {
        SupplierPayment original = findById(id);
        if (original.getReversesPaymentId() != null) {
            throw new IllegalStateException("Cannot reverse a reversing entry");
        }
        if (original.getReversedAt() != null) {
            throw new IllegalStateException("This payment has already been reversed");
        }

        LocalDate today = LocalDate.now();
        String actor = currentUserId();
        LocalDateTime now = LocalDateTime.now();
        String number = original.getAppliedFromAdvance() ? null : documentNumberService.next(DocumentKind.PAYMENT_VOUCHER, today);

        SupplierPayment reversal = SupplierPayment.builder()
                .id(UniqueIdResolver.resolve(supplierPaymentRepository::existsById))
                .voucherNumber(number)
                .financialYear(number != null ? FinancialYear.of(today) : null)
                .direction(original.getDirection())
                .vendorId(original.getVendorId())
                .vendorName(original.getVendorName())
                .supplierInvoiceId(original.getSupplierInvoiceId())
                .bookingId(original.getBookingId())
                .currencyCode(original.getCurrencyCode())
                .fxRateToInr(original.getFxRateToInr())
                .amount(original.getAmount().negate())
                .amountInr(original.getAmountInr().negate())
                .tdsWithheld(original.getTdsWithheld())
                .paymentMode(original.getPaymentMode())
                .paidOn(today)
                .isAdvance(original.getIsAdvance())
                .appliedFromAdvance(original.getAppliedFromAdvance())
                .reversesPaymentId(original.getId())
                .notes(reason)
                .createdAt(now)
                .createdBy(actor)
                .build();
        supplierPaymentRepository.save(reversal);
        if (!original.getAppliedFromAdvance()) {
            postForPayment(reversal);
        }
        reversePaymentJournal(original);

        original.setReversedAt(now);
        original.setReversedBy(actor);
        original.setReversalReason(reason);
        supplierPaymentRepository.save(original);

        if (original.getSupplierInvoiceId() != null) {
            SupplierInvoice invoice = findInvoice(original.getSupplierInvoiceId());
            applySettlement(invoice, original.getAmount().negate());
        }

        auditService.recordCreate(AuditEntityType.SUPPLIER_PAYMENT, reversal.getId(), "Reversal of " + original.getId());
        log.info("Supplier payment reversed: original={}, reversal={}", original.getId(), reversal.getId());
        return toResponse(reversal);
    }

    @Transactional(readOnly = true)
    public SupplierPaymentResponse get(String id) {
        return toResponse(findById(id));
    }

    @Transactional(readOnly = true)
    public List<SupplierPaymentResponse> listForInvoice(String supplierInvoiceId) {
        return supplierPaymentRepository.findBySupplierInvoiceIdOrderByPaidOnAscCreatedAtAsc(supplierInvoiceId)
                .stream().map(SupplierPaymentService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<SupplierPaymentResponse> listForVendor(String vendorId) {
        return supplierPaymentRepository.findAll(
                (root, query, cb) -> cb.equal(root.get("vendorId"), vendorId),
                Sort.by(Sort.Direction.DESC, "paidOn")
        ).stream().map(SupplierPaymentService::toResponse).toList();
    }

    /**
     * The unallocated-advance lookup the FRD asks the final-bill review screen to show (US-ACC-3.2:
     * "the system presents all unallocated prepayments linked to that supplier"). A specific
     * advance row is not individually tracked once applied - see {@link #applyAdvance} - so this
     * returns every still-active advance row for the vendor alongside the one remaining pool total
     * they jointly fund, not a per-row remaining balance.
     */
    @Transactional(readOnly = true)
    public SupplierUnallocatedAdvancesResponse unallocatedAdvances(String vendorId) {
        Vendor vendor = findVendor(vendorId);
        List<SupplierPayment> activeAdvances = supplierPaymentRepository
                .findByVendorIdAndIsAdvanceTrueOrderByPaidOnAsc(vendorId).stream()
                .filter(p -> p.getReversedAt() == null)
                .toList();
        return SupplierUnallocatedAdvancesResponse.builder()
                .vendorId(vendor.getId())
                .vendorName(vendor.getName())
                .remainingAmount(remainingAdvancePool(vendorId))
                .advances(activeAdvances.stream().map(SupplierPaymentService::toAdvanceRow).toList())
                .build();
    }

    /**
     * Payment Advice PDF (FRD US-ACC-3.2). Read-only and transactional - PDF generation here is
     * in-memory formatting of already-saved figures, not the file-storage I/O blueprint §8.6
     * forbids inside a transaction (contrast {@code BookingDocumentService#downloadFile}, which
     * touches {@code FileStorageService} and is deliberately not transactional).
     */
    @Transactional(readOnly = true)
    public byte[] getAdvicePdf(String id) {
        SupplierPayment payment = findById(id);
        SupplierInvoice invoice = payment.getSupplierInvoiceId() != null ? findInvoice(payment.getSupplierInvoiceId()) : null;
        List<SupplierPayment> invoicePayments = invoice != null
                ? supplierPaymentRepository.findBySupplierInvoiceIdOrderByPaidOnAscCreatedAtAsc(invoice.getId())
                : List.of();
        Vendor vendor = vendorRepository.findById(payment.getVendorId()).orElse(null);
        Tenant agency = currentAgency();
        return PaymentAdvicePdfRenderer.write(payment, invoice, invoicePayments, vendor, agency);
    }

    // ---------------------------------------------------------------- internals

    private BigDecimal remainingAdvancePool(String vendorId) {
        BigDecimal totalAdvance = supplierPaymentRepository.findByVendorIdAndIsAdvanceTrueOrderByPaidOnAsc(vendorId)
                .stream().filter(p -> p.getReversedAt() == null).map(SupplierPayment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalApplied = supplierPaymentRepository.findByVendorIdAndAppliedFromAdvanceTrueOrderByPaidOnAsc(vendorId)
                .stream().filter(p -> p.getReversedAt() == null).map(SupplierPayment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return totalAdvance.subtract(totalApplied);
    }

    private Tenant currentAgency() {
        String tenantId = SecurityContextUtil.getCurrentUserOrThrow().tenantId();
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalStateException("Agency not found: " + tenantId));
    }

    private static SupplierAdvanceRowResponse toAdvanceRow(SupplierPayment p) {
        return SupplierAdvanceRowResponse.builder()
                .id(p.getId()).voucherNumber(p.getVoucherNumber()).paidOn(p.getPaidOn())
                .amount(p.getAmount()).amountInr(p.getAmountInr())
                .build();
    }

    private void postForPayment(SupplierPayment payment) {
        boolean isDebit = payment.getAmountInr().compareTo(BigDecimal.ZERO) >= 0;
        BigDecimal amount = payment.getAmount().abs();
        BigDecimal amountInr = payment.getAmountInr().abs();
        SupplierLedgerEntryType type = payment.getIsAdvance() ? SupplierLedgerEntryType.ADVANCE_PAID : SupplierLedgerEntryType.PAYMENT_MADE;
        String narration = payment.getReversesPaymentId() != null
                ? "Payment " + orId(payment.getVoucherNumber(), payment.getId()) + " reverses " + payment.getReversesPaymentId()
                : "Payment " + orId(payment.getVoucherNumber(), payment.getId()) + " recorded";

        supplierLedgerService.post(new SupplierLedgerPosting(
                payment.getVendorId(), payment.getPaidOn(), type,
                SupplierLedgerSourceType.SUPPLIER_PAYMENT, payment.getId(), payment.getVoucherNumber(), narration,
                payment.getBookingId(), payment.getCurrencyCode(), payment.getFxRateToInr(),
                isDebit ? amount : BigDecimal.ZERO, isDebit ? BigDecimal.ZERO : amount,
                isDebit ? amountInr : BigDecimal.ZERO, isDebit ? BigDecimal.ZERO : amountInr));
    }

    /**
     * Posting-rule-table rows 9/9a/9b (bill payment) and 10 (advance). A brand-new payment only -
     * a reversal row is handled by {@link #reversePaymentJournal}, per Rule 1.6.2.
     *
     * <p>Row 9a/9b (realized forex gain/loss) do not fire yet: {@code payment.fxRateToInr} is
     * always copied from {@code invoice.fxRateToInr} at the call site above (the known blocker
     * ACCOUNTING_EXPANSION_ARCHITECTURE.md §3.4 names - a settlement rate cannot yet differ from
     * the bill rate), so AP and Bank always move by the same INR amount and the variance is
     * always exactly zero. This method computes the variance correctly regardless, so Decision 3
     * (a real settlement-rate field) lights it up with no further change here.
     */
    private void postPaymentJournal(SupplierPayment payment, SupplierInvoice invoice) {
        BigDecimal amountInr = payment.getAmountInr();
        if (amountInr == null || amountInr.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        String bankCode = payment.getPaymentMode() == PaymentMode.CASH
                ? com.voyra.crm.enums.SystemAccount.CASH_IN_HAND.code()
                : com.voyra.crm.enums.SystemAccount.BANK_ACCOUNTS.code();
        String narration = "Payment " + orId(payment.getVoucherNumber(), payment.getId()) + " recorded";

        if (invoice != null) {
            BigDecimal apDebitInr = payment.getAmount().multiply(invoice.getFxRateToInr()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal variance = apDebitInr.subtract(amountInr);
            List<com.voyra.crm.models.JournalLinePosting> lines = new java.util.ArrayList<>(List.of(
                    com.voyra.crm.models.JournalLinePosting.debitParty(
                            com.voyra.crm.enums.SystemAccount.ACCOUNTS_PAYABLE.code(), "VENDOR", payment.getVendorId(), apDebitInr, narration)));
            if (variance.compareTo(BigDecimal.ZERO) > 0) {
                lines.add(com.voyra.crm.models.JournalLinePosting.debit(
                        com.voyra.crm.enums.SystemAccount.REALIZED_FOREX_LOSS.code(), variance, "Realized forex loss on settlement"));
            } else if (variance.compareTo(BigDecimal.ZERO) < 0) {
                lines.add(com.voyra.crm.models.JournalLinePosting.credit(
                        com.voyra.crm.enums.SystemAccount.REALIZED_FOREX_GAIN.code(), variance.abs(), "Realized forex gain on settlement"));
            }
            lines.add(com.voyra.crm.models.JournalLinePosting.credit(bankCode, amountInr, narration));
            journalService.post(new com.voyra.crm.models.JournalPosting(
                    payment.getPaidOn(), com.voyra.crm.enums.JournalSourceType.SUPPLIER_PAYMENT, payment.getId(),
                    com.voyra.crm.enums.JournalPurpose.SUPPLIER_PAYMENT_SETTLED, narration, payment.getBookingId(), null, lines));
        } else {
            journalService.post(new com.voyra.crm.models.JournalPosting(
                    payment.getPaidOn(), com.voyra.crm.enums.JournalSourceType.SUPPLIER_PAYMENT, payment.getId(),
                    com.voyra.crm.enums.JournalPurpose.SUPPLIER_ADVANCE_PAID, narration, payment.getBookingId(), null, List.of(
                            com.voyra.crm.models.JournalLinePosting.debitParty(
                                    com.voyra.crm.enums.SystemAccount.SUPPLIER_ADVANCES.code(), "VENDOR", payment.getVendorId(), amountInr, narration),
                            com.voyra.crm.models.JournalLinePosting.credit(bankCode, amountInr, narration))));
        }
    }

    /** Posting-rule-table row 11 - moves a balance from the vendor's advance pool onto this bill's payable. */
    private void postAdvanceAppliedJournal(SupplierPayment applied, SupplierInvoice invoice) {
        BigDecimal amountInr = applied.getAmountInr();
        if (amountInr == null || amountInr.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        String narration = "Advance applied to bill " + invoice.getId();
        journalService.post(new com.voyra.crm.models.JournalPosting(
                applied.getPaidOn(), com.voyra.crm.enums.JournalSourceType.SUPPLIER_PAYMENT, applied.getId(),
                com.voyra.crm.enums.JournalPurpose.SUPPLIER_ADVANCE_APPLIED, narration, invoice.getBookingId(), null, List.of(
                        com.voyra.crm.models.JournalLinePosting.debitParty(
                                com.voyra.crm.enums.SystemAccount.ACCOUNTS_PAYABLE.code(), "VENDOR", applied.getVendorId(), amountInr, narration),
                        com.voyra.crm.models.JournalLinePosting.creditParty(
                                com.voyra.crm.enums.SystemAccount.SUPPLIER_ADVANCES.code(), "VENDOR", applied.getVendorId(), amountInr, narration))));
    }

    /** Reverses whichever journal was actually posted for the ORIGINAL payment - at most one of the candidate purposes exists. */
    private void reversePaymentJournal(SupplierPayment original) {
        List<com.voyra.crm.enums.JournalPurpose> candidates = Boolean.TRUE.equals(original.getAppliedFromAdvance())
                ? List.of(com.voyra.crm.enums.JournalPurpose.SUPPLIER_ADVANCE_APPLIED)
                : List.of(com.voyra.crm.enums.JournalPurpose.SUPPLIER_PAYMENT_SETTLED,
                          com.voyra.crm.enums.JournalPurpose.SUPPLIER_ADVANCE_PAID);
        for (com.voyra.crm.enums.JournalPurpose purpose : candidates) {
            journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(
                    com.voyra.crm.enums.JournalSourceType.SUPPLIER_PAYMENT, original.getId(), purpose)
                    .ifPresent(entry -> journalService.reverse(entry.getId(),
                            "Payment " + orId(original.getVoucherNumber(), original.getId()) + " reversed"));
        }
    }

    /** delta is signed - positive when money/credit is applied toward the bill, negative when a payment against it is reversed. */
    private void applySettlement(SupplierInvoice invoice, BigDecimal delta) {
        invoice.setAmountPaid(invoice.getAmountPaid().add(delta));
        BigDecimal balanceDue = invoice.getGrandTotal().subtract(invoice.getAmountPaid()).subtract(invoice.getCreditNoteTotal());
        invoice.setBalanceDue(balanceDue);
        invoice.setBalanceDueInr(scaleToInr(balanceDue, invoice.getFxRateToInr()));
        invoice.setStatus(SupplierInvoiceLifecyclePolicy.deriveFromBalance(invoice.getGrandTotal(), balanceDue));
        supplierInvoiceRepository.save(invoice);
    }

    private Vendor findVendor(String vendorId) {
        return vendorRepository.findById(vendorId)
                .orElseThrow(() -> new IllegalArgumentException("Vendor not found: " + vendorId));
    }

    private SupplierInvoice findInvoice(String id) {
        return supplierInvoiceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Supplier bill not found: " + id));
    }

    private SupplierPayment findById(String id) {
        return supplierPaymentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + id));
    }

    private static BigDecimal scaleToInr(BigDecimal amount, BigDecimal fxRateToInr) {
        return amount.multiply(fxRateToInr).setScale(2, RoundingMode.HALF_UP);
    }

    private static String orId(String number, String id) {
        return number != null ? number : id;
    }

    private String currentUserId() {
        return SecurityContextUtil.getCurrentUserOrThrow().userId();
    }

    private static SupplierPaymentResponse toResponse(SupplierPayment p) {
        return SupplierPaymentResponse.builder()
                .id(p.getId()).voucherNumber(p.getVoucherNumber()).financialYear(p.getFinancialYear())
                .direction(p.getDirection()).vendorId(p.getVendorId()).vendorName(p.getVendorName())
                .supplierInvoiceId(p.getSupplierInvoiceId()).bookingId(p.getBookingId())
                .currencyCode(p.getCurrencyCode()).fxRateToInr(p.getFxRateToInr())
                .amount(p.getAmount()).amountInr(p.getAmountInr()).tdsWithheld(p.getTdsWithheld())
                .paymentMode(p.getPaymentMode()).instrumentRef(p.getInstrumentRef()).bankAccountLabel(p.getBankAccountLabel())
                .paidOn(p.getPaidOn()).isAdvance(p.getIsAdvance()).appliedFromAdvance(p.getAppliedFromAdvance())
                .reversesPaymentId(p.getReversesPaymentId()).reversedAt(p.getReversedAt())
                .reversedBy(p.getReversedBy()).reversalReason(p.getReversalReason())
                .notes(p.getNotes()).createdAt(p.getCreatedAt())
                .build();
    }
}
