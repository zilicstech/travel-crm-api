package com.voyra.crm.service;

import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.dto.PaymentReceiptApplyAdvanceRequest;
import com.voyra.crm.dto.PaymentReceiptRequest;
import com.voyra.crm.dto.PaymentReceiptResponse;
import com.voyra.crm.entity.Client;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.entity.PaymentReceipt;
import com.voyra.crm.enums.AuditEntityType;
import com.voyra.crm.enums.DocumentKind;
import com.voyra.crm.enums.InvoiceLifecycle;
import com.voyra.crm.enums.LedgerEntryType;
import com.voyra.crm.enums.LedgerSourceType;
import com.voyra.crm.enums.PaymentMode;
import com.voyra.crm.enums.ReceiptDirection;
import com.voyra.crm.models.LedgerPosting;
import com.voyra.crm.repository.InvoiceRepository;
import com.voyra.crm.repository.PaymentReceiptRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.FinancialYear;
import com.voyra.crm.util.InvoiceLifecyclePolicy;
import com.voyra.crm.util.ReceiptVoucherRenderer;
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
 * Records and reverses {@link PaymentReceipt} rows against an invoice or an issued proforma, and
 * is the only place a tax invoice's {@code amountReceived}/{@code balanceDue}/{@code status} are
 * recomputed from settlement facts (see {@link InvoiceLifecyclePolicy#deriveFromBalance}). A
 * proforma's advance receipts never move its status - only converting it to a tax invoice
 * (`InvoiceDocumentService#convertToTaxInvoice`) does that, by re-pointing them onto the new row.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentReceiptService {

    private final PaymentReceiptRepository paymentReceiptRepository;
    private final InvoiceRepository invoiceRepository;
    private final ClientService clientService;
    private final DocumentNumberService documentNumberService;
    private final AuditService auditService;
    private final CustomerLedgerService customerLedgerService;
    private final BookingAccountingSync bookingAccountingSync;

    @Transactional
    public PaymentReceiptResponse record(PaymentReceiptRequest request) {
        boolean hasInvoice = request.getInvoiceId() != null && !request.getInvoiceId().isBlank();
        return hasInvoice ? recordAgainstInvoice(request) : recordDeposit(request);
    }

    private PaymentReceiptResponse recordAgainstInvoice(PaymentReceiptRequest request) {
        Invoice invoice = invoiceRepository.findById(request.getInvoiceId())
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found: " + request.getInvoiceId()));
        InvoiceLifecyclePolicy.assertReceivable(invoice.getStatus());
        boolean isProforma = invoice.getStatus() == InvoiceLifecycle.PROFORMA_ISSUED;

        LocalDate receivedOn = request.getReceivedOn();
        String number = documentNumberService.next(DocumentKind.RECEIPT, receivedOn);
        String actor = currentUserId();

        PaymentReceipt receipt = PaymentReceipt.builder()
                .id(UniqueIdResolver.resolve(paymentReceiptRepository::existsById))
                .receiptNumber(number)
                .financialYear(FinancialYear.of(receivedOn))
                .direction(ReceiptDirection.RECEIPT)
                .invoiceId(invoice.getId())
                .clientId(invoice.getClientId())
                .bookingId(invoice.getBookingId())
                .currencyCode(invoice.getCurrencyCode())
                .fxRateToInr(invoice.getFxRateToInr())
                .amount(request.getAmount())
                .amountInr(scaleToInr(request.getAmount(), invoice.getFxRateToInr()))
                .paymentMode(request.getPaymentMode())
                .instrumentRef(request.getInstrumentRef())
                .bankAccountLabel(request.getBankAccountLabel())
                .receivedOn(receivedOn)
                .isAdvance(isProforma)
                .appliedFromAdvance(false)
                .notes(request.getNotes())
                .createdAt(LocalDateTime.now())
                .createdBy(actor)
                .build();
        paymentReceiptRepository.save(receipt);
        postForReceipt(receipt);

        if (!isProforma) {
            applySettlement(invoice);
        }
        bookingAccountingSync.syncPayment(invoice.getBookingId());

        auditService.recordCreate(AuditEntityType.PAYMENT_RECEIPT, receipt.getId(), receipt.getReceiptNumber());
        log.info("Receipt recorded: id={}, invoiceId={}, amount={}", receipt.getId(), invoice.getId(), receipt.getAmount());
        return toResponse(receipt);
    }

    /**
     * The customer wallet: money held for a client with nothing to attach it to yet - "a
     * frequent customer sends 5 lakh up front, and each new invoice draws it down." Always INR
     * (there is no invoice to inherit a currency/FX rate from). Posts a credit immediately, the
     * same as any other receipt - the client relationship changes the moment cash moves, not
     * when it is later matched to a bill.
     */
    private PaymentReceiptResponse recordDeposit(PaymentReceiptRequest request) {
        if (request.getClientId() == null || request.getClientId().isBlank()) {
            throw new IllegalArgumentException("clientId is required when invoiceId is omitted");
        }
        Client client = clientService.findAccessibleClient(request.getClientId());

        LocalDate receivedOn = request.getReceivedOn();
        String number = documentNumberService.next(DocumentKind.RECEIPT, receivedOn);
        String actor = currentUserId();

        PaymentReceipt receipt = PaymentReceipt.builder()
                .id(UniqueIdResolver.resolve(paymentReceiptRepository::existsById))
                .receiptNumber(number)
                .financialYear(FinancialYear.of(receivedOn))
                .direction(ReceiptDirection.RECEIPT)
                .invoiceId(null)
                .clientId(client.getId())
                .currencyCode("INR")
                .fxRateToInr(BigDecimal.ONE)
                .amount(request.getAmount())
                .amountInr(request.getAmount())
                .paymentMode(request.getPaymentMode())
                .instrumentRef(request.getInstrumentRef())
                .bankAccountLabel(request.getBankAccountLabel())
                .receivedOn(receivedOn)
                .isAdvance(true)
                .appliedFromAdvance(false)
                .notes(request.getNotes())
                .createdAt(LocalDateTime.now())
                .createdBy(actor)
                .build();
        paymentReceiptRepository.save(receipt);
        postForReceipt(receipt);

        auditService.recordCreate(AuditEntityType.PAYMENT_RECEIPT, receipt.getId(), receipt.getReceiptNumber());
        log.info("Deposit recorded: id={}, clientId={}, amount={}", receipt.getId(), client.getId(), receipt.getAmount());
        return toResponse(receipt);
    }

    /**
     * Moves part or all of a client's wallet balance onto a specific invoice - no ledger row,
     * the deposit already posted its own credit when it was recorded (mirrors {@code
     * SupplierPaymentService#applyAdvance}, AD-5). {@code advanceReceiptId} identifies which
     * deposit prompted this for the audit trail; the actual constraint checked is the client's
     * whole pooled wallet balance, same as the supplier side - a deposit is not individually
     * tracked once it is in the pool.
     */
    @Transactional
    public PaymentReceiptResponse applyAdvance(String invoiceId, PaymentReceiptApplyAdvanceRequest request) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found: " + invoiceId));
        InvoiceLifecyclePolicy.assertReceivable(invoice.getStatus());

        PaymentReceipt advance = paymentReceiptRepository.findById(request.getAdvanceReceiptId())
                .orElseThrow(() -> new IllegalArgumentException("Deposit not found: " + request.getAdvanceReceiptId()));
        if (!Boolean.TRUE.equals(advance.getIsAdvance()) || advance.getInvoiceId() != null || advance.getReversedAt() != null) {
            throw new IllegalArgumentException("This receipt is not an active deposit on account");
        }
        if (!advance.getClientId().equals(invoice.getClientId())) {
            throw new IllegalArgumentException("This deposit belongs to a different client");
        }

        BigDecimal remaining = remainingWallet(invoice.getClientId());
        if (request.getAmount().compareTo(remaining) > 0) {
            throw new IllegalStateException("Only " + remaining + " of the wallet remains unapplied for this client");
        }

        PaymentReceipt applied = applyFromWallet(invoice, request.getAmount(), "Applied from deposit " + advance.getId());
        auditService.recordCreate(AuditEntityType.PAYMENT_RECEIPT, invoice.getId(), "Wallet applied to " + invoice.getClientName());
        log.info("Wallet applied: advanceId={}, invoiceId={}, amount={}", advance.getId(), invoiceId, request.getAmount());
        return toResponse(applied);
    }

    /**
     * Called once, right after an invoice is finalised (see {@code InvoiceDocumentService#issue}):
     * draws down whatever the client's wallet already holds, automatically, up to the invoice's
     * own balance - the drawdown the client described ("as booking goes on that money will keep
     * on deducting from customer's balance"). A no-op when the wallet is empty or the invoice is
     * already settled some other way.
     */
    @Transactional
    public void applyAvailableWallet(Invoice invoice) {
        BigDecimal remaining = remainingWallet(invoice.getClientId());
        if (remaining.compareTo(BigDecimal.ZERO) <= 0 || invoice.getBalanceDue().compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal amount = remaining.min(invoice.getBalanceDue());
        applyFromWallet(invoice, amount, "Applied automatically from the client's wallet on finalise");
        log.info("Wallet auto-applied on issue: invoiceId={}, amount={}", invoice.getId(), amount);
    }

    private PaymentReceipt applyFromWallet(Invoice invoice, BigDecimal amount, String note) {
        PaymentReceipt applied = PaymentReceipt.builder()
                .id(UniqueIdResolver.resolve(paymentReceiptRepository::existsById))
                .direction(ReceiptDirection.RECEIPT)
                .invoiceId(invoice.getId())
                .clientId(invoice.getClientId())
                .bookingId(invoice.getBookingId())
                .currencyCode(invoice.getCurrencyCode())
                .fxRateToInr(invoice.getFxRateToInr())
                .amount(amount)
                .amountInr(scaleToInr(amount, invoice.getFxRateToInr()))
                .paymentMode(PaymentMode.ADJUSTMENT)
                .receivedOn(LocalDate.now())
                .isAdvance(false)
                .appliedFromAdvance(true)
                .notes(note)
                .createdAt(LocalDateTime.now())
                .createdBy(currentUserId())
                .build();
        paymentReceiptRepository.save(applied);
        // No ledger post - the deposit already posted its own credit when it was recorded.
        applySettlement(invoice);
        return applied;
    }

    /** Total deposits for this client, minus whatever has already been drawn from them. */
    private BigDecimal remainingWallet(String clientId) {
        BigDecimal totalDeposits = paymentReceiptRepository
                .findByClientIdAndInvoiceIdIsNullAndIsAdvanceTrueOrderByReceivedOnAsc(clientId)
                .stream().filter(r -> r.getReversedAt() == null).map(PaymentReceipt::getAmountInr)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalApplied = paymentReceiptRepository
                .findByClientIdAndAppliedFromAdvanceTrueOrderByReceivedOnAsc(clientId)
                .stream().filter(r -> r.getReversedAt() == null).map(PaymentReceipt::getAmountInr)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return totalDeposits.subtract(totalApplied);
    }

    /**
     * Writes a new, opposite-signed receipt against the same invoice and stamps the original as
     * reversed - the original's own amount is never edited, so what was actually received on
     * that date stays on the record.
     */
    @Transactional
    public PaymentReceiptResponse reverse(String id, String reason) {
        PaymentReceipt original = findById(id);
        if (original.getReversesReceiptId() != null) {
            throw new IllegalStateException("Cannot reverse a reversing entry");
        }
        if (original.getReversedAt() != null) {
            throw new IllegalStateException("This receipt has already been reversed");
        }

        // No invoice to settle for a pure deposit on account - and an applied-from-advance row
        // never posted its own ledger credit in the first place, so undoing it is a plain undo,
        // not a second reversal of money already reversed once via the deposit it drew from.
        Invoice invoice = original.getInvoiceId() != null
                ? invoiceRepository.findById(original.getInvoiceId())
                    .orElseThrow(() -> new IllegalStateException("Invoice not found: " + original.getInvoiceId()))
                : null;
        boolean isProforma = invoice != null && invoice.getStatus() == InvoiceLifecycle.PROFORMA_ISSUED;
        boolean appliedFromAdvance = Boolean.TRUE.equals(original.getAppliedFromAdvance());

        LocalDate today = LocalDate.now();
        String number = appliedFromAdvance ? null : documentNumberService.next(DocumentKind.RECEIPT, today);
        String actor = currentUserId();
        LocalDateTime now = LocalDateTime.now();

        PaymentReceipt reversal = PaymentReceipt.builder()
                .id(UniqueIdResolver.resolve(paymentReceiptRepository::existsById))
                .receiptNumber(number)
                .financialYear(number != null ? FinancialYear.of(today) : null)
                .direction(original.getDirection())
                .invoiceId(original.getInvoiceId())
                .clientId(original.getClientId())
                .bookingId(original.getBookingId())
                .currencyCode(original.getCurrencyCode())
                .fxRateToInr(original.getFxRateToInr())
                .amount(original.getAmount().negate())
                .amountInr(original.getAmountInr().negate())
                .paymentMode(original.getPaymentMode())
                .receivedOn(today)
                .isAdvance(original.getIsAdvance())
                .appliedFromAdvance(appliedFromAdvance)
                .reversesReceiptId(original.getId())
                .notes(reason)
                .createdAt(now)
                .createdBy(actor)
                .build();
        paymentReceiptRepository.save(reversal);
        if (!appliedFromAdvance) {
            postForReceipt(reversal);
        }

        original.setReversedAt(now);
        original.setReversedBy(actor);
        original.setReversalReason(reason);
        paymentReceiptRepository.save(original);

        if (invoice != null && !isProforma) {
            applySettlement(invoice);
        }
        if (invoice != null) {
            bookingAccountingSync.syncPayment(invoice.getBookingId());
        }

        auditService.recordCreate(AuditEntityType.PAYMENT_RECEIPT, reversal.getId(),
                reversal.getReceiptNumber() != null ? reversal.getReceiptNumber() : reversal.getId());
        log.info("Receipt reversed: original={}, reversal={}", original.getId(), reversal.getId());
        return toResponse(reversal);
    }

    @Transactional(readOnly = true)
    public PaymentReceiptResponse get(String id) {
        return toResponse(findAccessible(id));
    }

    @Transactional(readOnly = true)
    public byte[] getPdf(String id) {
        PaymentReceipt receipt = findAccessible(id);
        String invoiceNumber = receipt.getInvoiceId() != null
                ? invoiceRepository.findById(receipt.getInvoiceId()).map(Invoice::getInvoiceNumber).orElse(null)
                : null;
        return ReceiptVoucherRenderer.write(receipt, invoiceNumber);
    }

    @Transactional(readOnly = true)
    public Object list(String invoiceId, String clientId, Pageable pageable) {
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        List<Specification<PaymentReceipt>> predicates = new ArrayList<>();
        if (principal.isAgent()) {
            List<String> ownInvoiceIds = invoiceRepository.findIdsByAgentId(principal.userId());
            List<String> scoped = ownInvoiceIds.isEmpty() ? List.of("__none__") : ownInvoiceIds;
            predicates.add((root, query, cb) -> root.get("invoiceId").in(scoped));
        }
        if (invoiceId != null) {
            predicates.add((root, query, cb) -> cb.equal(root.get("invoiceId"), invoiceId));
        }
        if (clientId != null) {
            predicates.add((root, query, cb) -> cb.equal(root.get("clientId"), clientId));
        }
        Specification<PaymentReceipt> spec = Specification.allOf(predicates);

        if (pageable != null) {
            return PagedResponse.from(paymentReceiptRepository.findAll(spec, pageable), PaymentReceiptService::toResponse);
        }
        return paymentReceiptRepository.findAll(spec, Sort.by(Sort.Direction.DESC, "receivedOn"))
                .stream().map(PaymentReceiptService::toResponse).toList();
    }

    // ---------------------------------------------------------------- internals

    /**
     * Posts one ledger row per receipt row - original and reversal alike - keyed by that
     * receipt's own id, so the unique index never collides between them. A reversal's negative
     * amount naturally becomes a debit here, undoing the original credit in the client's running
     * balance without needing a distinct {@code LedgerEntryType}. Posted for every receipt,
     * advance or not: real cash moving is a fact about the client relationship the moment it
     * happens, independent of which invoice it currently sits against.
     */
    private void postForReceipt(PaymentReceipt receipt) {
        boolean isCredit = receipt.getAmountInr().compareTo(BigDecimal.ZERO) >= 0;
        BigDecimal amount = receipt.getAmount().abs();
        BigDecimal amountInr = receipt.getAmountInr().abs();
        String narration = receipt.getReversesReceiptId() != null
                ? "Receipt " + receipt.getReceiptNumber() + " reverses " + receipt.getReversesReceiptId()
                : "Receipt " + receipt.getReceiptNumber() + " recorded";

        customerLedgerService.post(new LedgerPosting(
                receipt.getClientId(), receipt.getReceivedOn(), LedgerEntryType.PAYMENT_RECEIVED,
                LedgerSourceType.RECEIPT, receipt.getId(), receipt.getReceiptNumber(), narration,
                receipt.getBookingId(), receipt.getCurrencyCode(), receipt.getFxRateToInr(),
                isCredit ? BigDecimal.ZERO : amount, isCredit ? amount : BigDecimal.ZERO,
                isCredit ? BigDecimal.ZERO : amountInr, isCredit ? amountInr : BigDecimal.ZERO));
    }

    /**
     * Sums every RECEIPT-direction row currently attached to this invoice id, including ones
     * re-pointed here from a converted proforma - once moved, they are real payments against
     * this real tax invoice regardless of the historical {@code isAdvance} label.
     */
    private void applySettlement(Invoice invoice) {
        List<PaymentReceipt> receipts = paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc(invoice.getId());
        BigDecimal received = receipts.stream()
                .filter(r -> r.getDirection() == ReceiptDirection.RECEIPT)
                .map(PaymentReceipt::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        invoice.setAmountReceived(received);
        BigDecimal balanceDue = invoice.getGrandTotal().subtract(received).subtract(invoice.getCreditNoteTotal());
        invoice.setBalanceDue(balanceDue);
        invoice.setBalanceDueInr(scaleToInr(balanceDue, invoice.getFxRateToInr()));
        invoice.setStatus(InvoiceLifecyclePolicy.deriveFromBalance(invoice.getGrandTotal(), balanceDue));
        invoiceRepository.save(invoice);
    }

    private PaymentReceipt findById(String id) {
        return paymentReceiptRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Receipt not found: " + id));
    }

    private PaymentReceipt findAccessible(String id) {
        PaymentReceipt receipt = findById(id);
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        if (principal.isAgent()) {
            Invoice invoice = receipt.getInvoiceId() != null ? invoiceRepository.findById(receipt.getInvoiceId()).orElse(null) : null;
            if (invoice == null || !invoice.getAgentId().equals(principal.userId())) {
                throw new AccessDeniedException("This receipt is not accessible to you");
            }
        }
        return receipt;
    }

    private static BigDecimal scaleToInr(BigDecimal amount, BigDecimal fxRateToInr) {
        return amount.multiply(fxRateToInr).setScale(2, RoundingMode.HALF_UP);
    }

    private String currentUserId() {
        return SecurityContextUtil.getCurrentUserOrThrow().userId();
    }

    private static PaymentReceiptResponse toResponse(PaymentReceipt r) {
        return PaymentReceiptResponse.builder()
                .id(r.getId()).receiptNumber(r.getReceiptNumber()).financialYear(r.getFinancialYear())
                .direction(r.getDirection()).invoiceId(r.getInvoiceId()).clientId(r.getClientId())
                .bookingId(r.getBookingId()).currencyCode(r.getCurrencyCode()).fxRateToInr(r.getFxRateToInr())
                .amount(r.getAmount()).amountInr(r.getAmountInr()).paymentMode(r.getPaymentMode())
                .instrumentRef(r.getInstrumentRef()).bankAccountLabel(r.getBankAccountLabel())
                .receivedOn(r.getReceivedOn()).isAdvance(r.getIsAdvance()).appliedFromAdvance(r.getAppliedFromAdvance())
                .reversesReceiptId(r.getReversesReceiptId()).reversedAt(r.getReversedAt())
                .reversedBy(r.getReversedBy()).reversalReason(r.getReversalReason())
                .notes(r.getNotes()).createdAt(r.getCreatedAt()).createdBy(r.getCreatedBy())
                .build();
    }
}
