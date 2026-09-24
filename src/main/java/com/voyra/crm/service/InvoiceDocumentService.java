package com.voyra.crm.service;

import com.voyra.crm.dto.AuditChange;
import com.voyra.crm.dto.InvoiceDraftRequest;
import com.voyra.crm.dto.InvoiceLineItemRequest;
import com.voyra.crm.dto.InvoiceLineItemResponse;
import com.voyra.crm.dto.InvoiceListItemResponse;
import com.voyra.crm.dto.InvoiceResponse;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.BookingPassenger;
import com.voyra.crm.entity.BookingSector;
import com.voyra.crm.entity.Client;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.entity.InvoiceLineItem;
import com.voyra.crm.entity.PaymentReceipt;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.enums.AuditEntityType;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.DocumentKind;
import com.voyra.crm.enums.FxRateSource;
import com.voyra.crm.enums.InvoiceDocumentType;
import com.voyra.crm.enums.InvoiceLifecycle;
import com.voyra.crm.enums.InvoiceServiceCategory;
import com.voyra.crm.enums.LedgerEntryType;
import com.voyra.crm.enums.LedgerSourceType;
import com.voyra.crm.enums.TaxTreatment;
import com.voyra.crm.models.LedgerPosting;
import com.voyra.crm.models.TaxComputationRequest;
import com.voyra.crm.models.TaxComputationResult;
import com.voyra.crm.repository.BookingPassengerRepository;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.BookingSectorRepository;
import com.voyra.crm.repository.InvoiceLineItemRepository;
import com.voyra.crm.repository.InvoiceRepository;
import com.voyra.crm.repository.PaymentReceiptRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.AuditSnapshot;
import com.voyra.crm.util.BookingInvoiceLineBuilder;
import com.voyra.crm.util.FinancialYear;
import com.voyra.crm.util.InvoiceLifecyclePolicy;
import com.voyra.crm.util.InvoicePdfRenderer;
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
import java.util.Map;

/**
 * Draft CRUD, issue, proforma issue/conversion, and cancel for tax invoices. A draft's tax
 * figures are recomputed via {@link TaxEngine} on every save; once {@link InvoiceLifecycle#DRAFT}
 * is left, nothing here ever recomputes them again - see {@code util.InvoiceLifecyclePolicy}.
 * Every receipt-driven settlement transition (PARTIALLY_PAID, PAID) is applied by
 * {@code PaymentReceiptService}, not here.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InvoiceDocumentService {

    private static final String[] AUDITED = { "status", "cancelReason" };

    private final InvoiceRepository invoiceRepository;
    private final InvoiceLineItemRepository invoiceLineItemRepository;
    private final PaymentReceiptRepository paymentReceiptRepository;
    private final BookingRepository bookingRepository;
    private final BookingPassengerRepository bookingPassengerRepository;
    private final BookingSectorRepository bookingSectorRepository;
    private final ClientService clientService;
    private final TenantRepository tenantRepository;
    private final TaxEngine taxEngine;
    private final DocumentNumberService documentNumberService;
    private final AuditService auditService;
    private final CustomerLedgerService customerLedgerService;
    private final BookingAccountingSync bookingAccountingSync;

    @Transactional
    public InvoiceResponse createDraft(InvoiceDraftRequest request) {
        if (request.getBookingId() == null || request.getBookingId().isBlank()) {
            throw new IllegalArgumentException("bookingId is required");
        }
        Booking booking = bookingRepository.findById(request.getBookingId())
                .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + request.getBookingId()));
        if (booking.getBookingStatus() == BookingStatus.CANCELLED) {
            throw new IllegalStateException("Cannot invoice a cancelled booking");
        }
        // Several tax invoices are legal against one booking now (advance + balance) - the
        // invariant that actually needs enforcing is "never two editable drafts competing for
        // the same booking", matching the DB's idx_invoice_booking_draft partial unique index.
        if (invoiceRepository.existsByBookingIdAndDocumentTypeAndStatus(
                booking.getId(), InvoiceDocumentType.TAX_INVOICE, InvoiceLifecycle.DRAFT)) {
            throw new IllegalStateException("Finish or cancel the existing draft before starting another");
        }

        Client client = clientService.findAccessibleClient(booking.getClientId());
        Tenant agency = currentAgency();
        InvoiceServiceCategory category = InvoiceServiceCategory.forBooking(
                booking.getType(), Boolean.TRUE.equals(booking.getInternationalTrip()));

        Invoice invoice = Invoice.builder()
                .id(UniqueIdResolver.resolve(invoiceRepository::existsById))
                .documentType(InvoiceDocumentType.TAX_INVOICE)
                .status(InvoiceLifecycle.DRAFT)
                .serviceCategory(category)
                .clientId(client.getId())
                .clientName(client.getName())
                .clientGstin(client.getGstin())
                .clientStateCode(client.getStateCode())
                .billingAddress(client.getBillingAddress())
                .agencyLegalName(agency.getLegalName() != null ? agency.getLegalName() : agency.getAgencyName())
                .agencyGstin(agency.getGstNumber())
                .agencyStateCode(agency.getStateCode())
                .agencyAddress(agency.getAddress())
                .bookingId(booking.getId())
                .agentId(booking.getAgentId())
                .createdAt(LocalDateTime.now())
                .createdBy(currentUserId())
                .build();

        applyDraftFields(invoice, request);
        List<InvoiceLineItemRequest> lineInputs = resolveLines(booking, category, request.getLines());
        List<InvoiceLineItem> lines = recomputeLinesAndTotals(invoice, lineInputs);
        invoiceRepository.save(invoice);
        invoiceLineItemRepository.saveAll(lines);

        log.info("Invoice draft created: id={}, bookingId={}", invoice.getId(), booking.getId());
        return toResponse(invoice, lines);
    }

    @Transactional
    public InvoiceResponse updateDraft(String id, InvoiceDraftRequest request) {
        Invoice invoice = findById(id);
        InvoiceLifecyclePolicy.assertEditable(invoice.getStatus());
        Booking booking = bookingRepository.findById(invoice.getBookingId())
                .orElseThrow(() -> new IllegalStateException("Booking not found: " + invoice.getBookingId()));
        InvoiceServiceCategory category = invoice.getServiceCategory() != null ? invoice.getServiceCategory()
                : InvoiceServiceCategory.forBooking(booking.getType(), Boolean.TRUE.equals(booking.getInternationalTrip()));
        invoice.setServiceCategory(category);

        applyDraftFields(invoice, request);
        invoice.setUpdatedAt(LocalDateTime.now());
        invoice.setUpdatedBy(currentUserId());

        List<InvoiceLineItemRequest> lineInputs = resolveLines(booking, category, request.getLines());
        List<InvoiceLineItem> lines = recomputeLinesAndTotals(invoice, lineInputs);
        invoiceRepository.save(invoice);
        invoiceLineItemRepository.deleteByInvoiceId(id);
        invoiceLineItemRepository.saveAll(lines);

        log.info("Invoice draft updated: id={}", id);
        return toResponse(invoice, lines);
    }

    /** Falls back to the pre-redesign generic series for an invoice with no category - only
     *  ever a pre-existing draft/proforma created before this column existed. */
    private DocumentKind invoiceDocumentKind(InvoiceServiceCategory category) {
        return category != null ? DocumentKind.forInvoiceCategory(category) : DocumentKind.TAX_INVOICE;
    }

    /**
     * The heart of ACCOUNTING_REDESIGN_SPEC.md §5.1: an explicit, non-empty {@code lines} list
     * from the caller still wins (existing tests and any transitional manual entry keep
     * working), but the normal path is the booking's own captured passengers - no line is ever
     * hand-typed once a booking has them. See {@code BookingInvoiceLineBuilder}.
     */
    private List<InvoiceLineItemRequest> resolveLines(Booking booking, InvoiceServiceCategory category,
                                                        List<InvoiceLineItemRequest> requestedLines) {
        if (requestedLines != null && !requestedLines.isEmpty()) {
            return requestedLines;
        }
        List<BookingPassenger> passengers = bookingPassengerRepository.findByBookingIdOrderBySortOrderAsc(booking.getId());
        Map<String, List<BookingSector>> sectorsByPassenger = passengers.isEmpty() ? Map.of()
                : bookingSectorRepository.findByBookingPassengerIdInOrderBySortOrderAsc(
                        passengers.stream().map(BookingPassenger::getId).toList())
                    .stream().collect(java.util.stream.Collectors.groupingBy(BookingSector::getBookingPassengerId));
        return BookingInvoiceLineBuilder.build(booking, category, passengers, sectorsByPassenger);
    }

    @Transactional
    public void deleteDraft(String id) {
        Invoice invoice = findById(id);
        InvoiceLifecyclePolicy.assertDeletable(invoice.getStatus());
        invoiceLineItemRepository.deleteByInvoiceId(id);
        invoiceRepository.delete(invoice);
        log.info("Draft invoice deleted: id={}", id);
    }

    @Transactional(readOnly = true)
    public InvoiceResponse get(String id) {
        return toResponse(findAccessibleInvoice(id));
    }

    @Transactional(readOnly = true)
    public byte[] getPdf(String id) {
        Invoice invoice = findAccessibleInvoice(id);
        List<InvoiceLineItem> lines = invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc(id);
        return InvoicePdfRenderer.write(invoice, lines);
    }

    @Transactional(readOnly = true)
    public Object list(InvoiceLifecycle status, String clientId, String bookingId,
                        LocalDate from, LocalDate to, Pageable pageable) {
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        List<Specification<Invoice>> predicates = new ArrayList<>();
        if (principal.isAgent()) {
            predicates.add((root, query, cb) -> cb.equal(root.get("agentId"), principal.userId()));
        }
        if (status != null) {
            predicates.add((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (clientId != null) {
            predicates.add((root, query, cb) -> cb.equal(root.get("clientId"), clientId));
        }
        if (bookingId != null) {
            predicates.add((root, query, cb) -> cb.equal(root.get("bookingId"), bookingId));
        }
        if (from != null) {
            predicates.add((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("invoiceDate"), from));
        }
        if (to != null) {
            predicates.add((root, query, cb) -> cb.lessThanOrEqualTo(root.get("invoiceDate"), to));
        }
        Specification<Invoice> spec = Specification.allOf(predicates);

        if (pageable != null) {
            return PagedResponse.from(invoiceRepository.findAll(spec, pageable), InvoiceDocumentService::toListItem);
        }
        return invoiceRepository.findAll(spec, Sort.by(Sort.Direction.DESC, "createdAt"))
                .stream().map(InvoiceDocumentService::toListItem).toList();
    }

    @Transactional
    public InvoiceResponse issue(String id) {
        Invoice invoice = findById(id);
        InvoiceLifecyclePolicy.assertIssuable(invoice.getStatus());

        List<InvoiceLineItem> lines = invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc(id);
        if (lines.isEmpty()) {
            throw new IllegalStateException("At least one line is required to issue an invoice");
        }

        LocalDate today = LocalDate.now();
        String number = documentNumberService.next(invoiceDocumentKind(invoice.getServiceCategory()), today);

        invoice.setInvoiceNumber(number);
        invoice.setFinancialYear(FinancialYear.of(today));
        invoice.setStatus(InvoiceLifecycle.ISSUED);
        invoice.setInvoiceDate(today);
        invoice.setIssuedAt(LocalDateTime.now());
        invoice.setIssuedBy(currentUserId());
        invoice.setFxLockedAt(LocalDateTime.now());
        invoiceRepository.save(invoice);
        postInvoiceRaised(invoice);
        bookingAccountingSync.syncPayment(invoice.getBookingId());

        auditService.recordCreate(AuditEntityType.INVOICE, invoice.getId(), invoice.getInvoiceNumber());
        log.info("Invoice issued: id={}, number={}", invoice.getId(), number);
        return toResponse(invoice, lines);
    }

    @Transactional
    public InvoiceResponse issueProforma(String id) {
        Invoice invoice = findById(id);
        InvoiceLifecyclePolicy.assertProformaIssuable(invoice.getStatus());

        List<InvoiceLineItem> lines = invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc(id);
        if (lines.isEmpty()) {
            throw new IllegalStateException("At least one line is required to issue a proforma");
        }

        LocalDate today = LocalDate.now();
        String number = documentNumberService.next(DocumentKind.PROFORMA, today);

        invoice.setInvoiceNumber(number);
        invoice.setFinancialYear(FinancialYear.of(today));
        invoice.setDocumentType(InvoiceDocumentType.PROFORMA);
        invoice.setStatus(InvoiceLifecycle.PROFORMA_ISSUED);
        invoice.setInvoiceDate(today);
        invoice.setIssuedAt(LocalDateTime.now());
        invoice.setIssuedBy(currentUserId());
        invoice.setFxLockedAt(LocalDateTime.now());
        invoiceRepository.save(invoice);

        auditService.recordCreate(AuditEntityType.INVOICE, invoice.getId(), invoice.getInvoiceNumber());
        log.info("Proforma issued: id={}, number={}", invoice.getId(), number);
        return toResponse(invoice, lines);
    }

    /**
     * Creates a new TAX_INVOICE row rather than mutating the proforma in place, so both series
     * stay immutable once issued. Advance receipts against the proforma re-point onto the new
     * row in the same transaction, and the proforma itself moves to CANCELLED - never deleted,
     * since its number must stay in the series. Tax figures are copied verbatim (same client,
     * booking, currency and FX rate as the proforma), never recomputed.
     */
    @Transactional
    public InvoiceResponse convertToTaxInvoice(String proformaId) {
        Invoice proforma = findById(proformaId);
        InvoiceLifecyclePolicy.assertConvertible(proforma.getStatus());

        List<InvoiceLineItem> proformaLines = invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc(proformaId);
        LocalDate today = LocalDate.now();
        String number = documentNumberService.next(invoiceDocumentKind(proforma.getServiceCategory()), today);
        String newId = UniqueIdResolver.resolve(invoiceRepository::existsById);
        LocalDateTime now = LocalDateTime.now();
        String actor = currentUserId();

        Invoice taxInvoice = proforma.toBuilder()
                .id(newId)
                .invoiceNumber(number)
                .financialYear(FinancialYear.of(today))
                .documentType(InvoiceDocumentType.TAX_INVOICE)
                .status(InvoiceLifecycle.ISSUED)
                .invoiceDate(today)
                .issuedAt(now)
                .issuedBy(actor)
                .fxLockedAt(now)
                .supersedesInvoiceId(proforma.getId())
                .amountReceived(BigDecimal.ZERO)
                .creditNoteTotal(BigDecimal.ZERO)
                .cancelledAt(null)
                .cancelledBy(null)
                .cancelReason(null)
                .createdAt(now)
                .createdBy(actor)
                .updatedAt(null)
                .updatedBy(null)
                .build();

        List<InvoiceLineItem> newLines = new ArrayList<>();
        for (InvoiceLineItem line : proformaLines) {
            newLines.add(line.toBuilder()
                    .id(UniqueIdResolver.resolve(invoiceLineItemRepository::existsById))
                    .invoiceId(newId)
                    .createdAt(now)
                    .build());
        }

        List<PaymentReceipt> advances = paymentReceiptRepository.findByInvoiceIdAndIsAdvanceTrue(proforma.getId());
        BigDecimal movedReceived = BigDecimal.ZERO;
        for (PaymentReceipt advance : advances) {
            advance.setInvoiceId(newId);
            movedReceived = movedReceived.add(advance.getAmount());
        }

        BigDecimal balanceDue = taxInvoice.getGrandTotal().subtract(movedReceived);
        taxInvoice.setAmountReceived(movedReceived);
        taxInvoice.setBalanceDue(balanceDue);
        taxInvoice.setBalanceDueInr(scaleToInr(balanceDue, taxInvoice.getFxRateToInr()));
        taxInvoice.setStatus(InvoiceLifecyclePolicy.deriveFromBalance(taxInvoice.getGrandTotal(), balanceDue));

        invoiceRepository.save(taxInvoice);
        invoiceLineItemRepository.saveAll(newLines);
        if (!advances.isEmpty()) {
            paymentReceiptRepository.saveAll(advances);
        }
        postInvoiceRaised(taxInvoice);
        bookingAccountingSync.syncPayment(taxInvoice.getBookingId());

        proforma.setStatus(InvoiceLifecycle.CANCELLED);
        proforma.setCancelledAt(now);
        proforma.setCancelledBy(actor);
        proforma.setCancelReason("Converted to " + number);
        invoiceRepository.save(proforma);

        auditService.recordCreate(AuditEntityType.INVOICE, taxInvoice.getId(), taxInvoice.getInvoiceNumber());
        log.info("Proforma {} converted to tax invoice {}", proforma.getInvoiceNumber(), number);
        return toResponse(taxInvoice, newLines);
    }

    @Transactional
    public InvoiceResponse cancel(String id, String reason) {
        Invoice invoice = findById(id);
        InvoiceLifecyclePolicy.assertCancellable(invoice.getStatus());
        if (invoice.getAmountReceived().compareTo(BigDecimal.ZERO) > 0) {
            String hint = invoice.getDocumentType() == InvoiceDocumentType.TAX_INVOICE
                    ? "issue a credit note instead" : "reverse the receipts first";
            throw new IllegalStateException("This invoice has receipts against it - " + hint);
        }

        Map<String, String> before = AuditSnapshot.of(invoice, AUDITED);
        boolean hadLedgerDebit = invoice.getDocumentType() == InvoiceDocumentType.TAX_INVOICE;
        invoice.setStatus(InvoiceLifecycle.CANCELLED);
        invoice.setCancelledAt(LocalDateTime.now());
        invoice.setCancelledBy(currentUserId());
        invoice.setCancelReason(reason);
        invoiceRepository.save(invoice);
        if (hadLedgerDebit) {
            postCancellationReversal(invoice);
        }
        bookingAccountingSync.syncPayment(invoice.getBookingId());

        List<AuditChange> changes = AuditSnapshot.diff(before, AuditSnapshot.of(invoice, AUDITED));
        auditService.recordUpdate(AuditEntityType.INVOICE, invoice.getId(), invoice.getInvoiceNumber(), changes);
        log.info("Invoice cancelled: id={}", id);
        return toResponse(invoice);
    }

    // ---------------------------------------------------------------- internals

    /**
     * Debits the client for the full grand total the moment a real tax invoice exists - whether
     * from a direct issue or a proforma conversion. This is deliberately independent of any
     * advance already posted against the proforma (see {@code PaymentReceiptService}): that
     * credit was posted against the client, not this invoice, so debiting the full total here and
     * letting the two net out in the statement is what keeps
     * {@code SUM(debit_inr - credit_inr) == SUM(balance_due_inr)} true after a conversion.
     */
    private void postInvoiceRaised(Invoice invoice) {
        customerLedgerService.post(new LedgerPosting(
                invoice.getClientId(), invoice.getInvoiceDate(), LedgerEntryType.INVOICE_RAISED,
                LedgerSourceType.INVOICE, invoice.getId(), invoice.getInvoiceNumber(),
                "Tax invoice " + invoice.getInvoiceNumber() + " raised", invoice.getBookingId(),
                invoice.getCurrencyCode(), invoice.getFxRateToInr(),
                invoice.getGrandTotal(), BigDecimal.ZERO, invoice.getGrandTotalInr(), BigDecimal.ZERO));
    }

    /** Undoes the INVOICE_RAISED debit for a tax invoice cancelled before any receipt exists against it. */
    private void postCancellationReversal(Invoice invoice) {
        customerLedgerService.post(new LedgerPosting(
                invoice.getClientId(), LocalDate.now(), LedgerEntryType.REVERSAL,
                LedgerSourceType.INVOICE, invoice.getId(), invoice.getInvoiceNumber(),
                "Tax invoice " + invoice.getInvoiceNumber() + " cancelled: " + invoice.getCancelReason(),
                invoice.getBookingId(), invoice.getCurrencyCode(), invoice.getFxRateToInr(),
                BigDecimal.ZERO, invoice.getGrandTotal(), BigDecimal.ZERO, invoice.getGrandTotalInr()));
    }

    private void applyDraftFields(Invoice invoice, InvoiceDraftRequest request) {
        String currencyCode = request.getCurrencyCode() != null && !request.getCurrencyCode().isBlank()
                ? request.getCurrencyCode().toUpperCase() : "INR";
        invoice.setCurrencyCode(currencyCode);

        if ("INR".equals(currencyCode)) {
            invoice.setFxRateToInr(BigDecimal.ONE);
            invoice.setFxRateSource(FxRateSource.INR_IDENTITY);
        } else {
            if (request.getFxRateToInr() == null || request.getFxRateToInr().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("fxRateToInr is required and must be positive when currencyCode is not INR");
            }
            invoice.setFxRateToInr(request.getFxRateToInr());
            invoice.setFxRateSource(FxRateSource.MANUAL);
        }

        invoice.setSupplyNature(request.getSupplyNature());
        invoice.setPlaceOfSupplyOverride(request.getPlaceOfSupplyCodeOverride());
        invoice.setExportOfServiceRequested(Boolean.TRUE.equals(request.getExportOfServiceRequested()));
        invoice.setDueDate(request.getDueDate());
        invoice.setNotes(request.getNotes());
        invoice.setTerms(request.getTerms());
    }

    /**
     * TaxEngine is called once per line (never once on an aggregate) so that summing every
     * line's own amounts is always exactly the header total - no separate rounding path to
     * reconcile. TCS has no threshold tiering active yet (see TaxEngine), so summing each
     * line's own TCS is mathematically identical to computing it once on the aggregate base.
     */
    private List<InvoiceLineItem> recomputeLinesAndTotals(Invoice invoice, List<InvoiceLineItemRequest> lineRequests) {
        List<InvoiceLineItem> lines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal discountTotal = BigDecimal.ZERO;
        BigDecimal taxableValue = BigDecimal.ZERO;
        BigDecimal cgst = BigDecimal.ZERO;
        BigDecimal sgst = BigDecimal.ZERO;
        BigDecimal igst = BigDecimal.ZERO;
        BigDecimal tcsBase = BigDecimal.ZERO;
        BigDecimal tcsAmount = BigDecimal.ZERO;
        BigDecimal grandTotal = BigDecimal.ZERO;
        TaxTreatment treatment = null;
        String placeOfSupply = null;
        BigDecimal tcsRate = BigDecimal.ZERO;
        String tcsSection = null;

        if (lineRequests == null || lineRequests.isEmpty()) {
            // No lines yet: still resolve treatment/place-of-supply so the NOT NULL header
            // columns are meaningful, using a zero amount purely for that resolution.
            TaxComputationResult r = taxEngine.compute(new TaxComputationRequest(
                    invoice.getClientId(), BigDecimal.ZERO, invoice.getSupplyNature(),
                    invoice.getPlaceOfSupplyOverride(), invoice.getExportOfServiceRequested(), invoice.getCurrencyCode()));
            treatment = r.taxTreatment();
            placeOfSupply = r.placeOfSupplyCode();
        } else {
            int sortOrder = 0;
            for (InvoiceLineItemRequest req : lineRequests) {
                BigDecimal discount = req.getDiscountAmount() != null ? req.getDiscountAmount() : BigDecimal.ZERO;
                BigDecimal lineSubtotal = req.getQuantity().multiply(req.getUnitPrice()).setScale(2, RoundingMode.HALF_UP);
                BigDecimal taxableInput = lineSubtotal.subtract(discount);

                TaxComputationResult r = taxEngine.compute(new TaxComputationRequest(
                        invoice.getClientId(), taxableInput, invoice.getSupplyNature(),
                        invoice.getPlaceOfSupplyOverride(), invoice.getExportOfServiceRequested(), invoice.getCurrencyCode()));

                InvoiceLineItem line = InvoiceLineItem.builder()
                        .id(UniqueIdResolver.resolve(invoiceLineItemRepository::existsById))
                        .invoiceId(invoice.getId())
                        .sortOrder(sortOrder++)
                        .description(req.getDescription())
                        // The line-item form has no SAC field of its own - fall back to the
                        // resolved GST slab's SAC so GSTR-1's SAC-wise grouping (§3.2) is never
                        // silently null. An explicit per-line SAC (a future finer-grained UI)
                        // still wins when supplied.
                        .sacCode(req.getSacCode() != null && !req.getSacCode().isBlank() ? req.getSacCode() : r.sacCode())
                        .serviceType(req.getServiceType())
                        .quantity(req.getQuantity())
                        .unitPrice(req.getUnitPrice())
                        .lineSubtotal(lineSubtotal)
                        .discountAmount(discount)
                        .taxablePercent(derivedTaxablePercent(taxableInput, r.taxableValue()))
                        .taxableValue(r.taxableValue())
                        .gstRatePercent(r.gstRatePercent())
                        .cgstRatePercent(r.cgstRatePercent())
                        .sgstRatePercent(r.sgstRatePercent())
                        .igstRatePercent(r.igstRatePercent())
                        .cgstAmount(r.cgstAmount())
                        .sgstAmount(r.sgstAmount())
                        .igstAmount(r.igstAmount())
                        .lineTotal(taxableInput.add(r.gstTotal()))
                        .createdAt(LocalDateTime.now())
                        .build();
                lines.add(line);

                subtotal = subtotal.add(lineSubtotal);
                discountTotal = discountTotal.add(discount);
                taxableValue = taxableValue.add(r.taxableValue());
                cgst = cgst.add(r.cgstAmount());
                sgst = sgst.add(r.sgstAmount());
                igst = igst.add(r.igstAmount());
                tcsBase = tcsBase.add(r.tcsBaseAmount());
                tcsAmount = tcsAmount.add(r.tcsAmount());
                grandTotal = grandTotal.add(r.grandTotal());
                treatment = r.taxTreatment();
                placeOfSupply = r.placeOfSupplyCode();
                tcsRate = r.tcsRatePercent();
                tcsSection = r.tcsSection();
            }
        }

        BigDecimal gstTotal = cgst.add(sgst).add(igst);
        BigDecimal fx = invoice.getFxRateToInr();

        invoice.setPlaceOfSupplyCode(placeOfSupply);
        invoice.setTaxTreatment(treatment);
        invoice.setSubtotal(subtotal);
        invoice.setDiscountTotal(discountTotal);
        invoice.setTaxableValue(taxableValue);
        invoice.setCgstAmount(cgst);
        invoice.setSgstAmount(sgst);
        invoice.setIgstAmount(igst);
        invoice.setGstTotal(gstTotal);
        invoice.setTcsRatePercent(tcsRate);
        invoice.setTcsSection(tcsSection);
        invoice.setTcsBaseAmount(tcsBase);
        invoice.setTcsAmount(tcsAmount);
        invoice.setRoundOff(BigDecimal.ZERO);
        invoice.setGrandTotal(grandTotal);
        invoice.setTaxableValueInr(scaleToInr(taxableValue, fx));
        invoice.setGstTotalInr(scaleToInr(gstTotal, fx));
        invoice.setTcsAmountInr(scaleToInr(tcsAmount, fx));
        invoice.setGrandTotalInr(scaleToInr(grandTotal, fx));
        invoice.setBalanceDue(grandTotal.subtract(invoice.getAmountReceived()).subtract(invoice.getCreditNoteTotal()));
        invoice.setBalanceDueInr(scaleToInr(invoice.getBalanceDue(), fx));

        return lines;
    }

    private static BigDecimal scaleToInr(BigDecimal amount, BigDecimal fxRateToInr) {
        return amount.multiply(fxRateToInr).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal derivedTaxablePercent(BigDecimal input, BigDecimal taxableValue) {
        if (input.compareTo(BigDecimal.ZERO) == 0) {
            return new BigDecimal("100.000");
        }
        return taxableValue.multiply(BigDecimal.valueOf(100)).divide(input, 3, RoundingMode.HALF_UP);
    }

    private Invoice findById(String id) {
        return invoiceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found: " + id));
    }

    private Invoice findAccessibleInvoice(String id) {
        Invoice invoice = findById(id);
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        if (principal.isAgent() && !invoice.getAgentId().equals(principal.userId())) {
            throw new AccessDeniedException("This invoice is not accessible to you");
        }
        return invoice;
    }

    private Tenant currentAgency() {
        String tenantId = SecurityContextUtil.getCurrentUserOrThrow().tenantId();
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalStateException("Agency not found: " + tenantId));
    }

    private String currentUserId() {
        return SecurityContextUtil.getCurrentUserOrThrow().userId();
    }

    private InvoiceResponse toResponse(Invoice invoice) {
        return toResponse(invoice, invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc(invoice.getId()));
    }

    private InvoiceResponse toResponse(Invoice i, List<InvoiceLineItem> lines) {
        return InvoiceResponse.builder()
                .id(i.getId()).invoiceNumber(i.getInvoiceNumber()).financialYear(i.getFinancialYear())
                .documentType(i.getDocumentType()).status(i.getStatus()).serviceCategory(i.getServiceCategory())
                .clientId(i.getClientId()).clientName(i.getClientName()).clientGstin(i.getClientGstin())
                .clientStateCode(i.getClientStateCode()).billingAddress(i.getBillingAddress())
                .agencyLegalName(i.getAgencyLegalName()).agencyGstin(i.getAgencyGstin())
                .agencyStateCode(i.getAgencyStateCode()).agencyAddress(i.getAgencyAddress())
                .bookingId(i.getBookingId()).leadId(i.getLeadId()).agentId(i.getAgentId())
                .placeOfSupplyCode(i.getPlaceOfSupplyCode()).supplyNature(i.getSupplyNature())
                .taxTreatment(i.getTaxTreatment()).placeOfSupplyOverride(i.getPlaceOfSupplyOverride())
                .exportOfServiceRequested(i.getExportOfServiceRequested())
                .currencyCode(i.getCurrencyCode()).fxRateToInr(i.getFxRateToInr())
                .fxRateSource(i.getFxRateSource()).fxLockedAt(i.getFxLockedAt())
                .subtotal(i.getSubtotal()).discountTotal(i.getDiscountTotal()).taxableValue(i.getTaxableValue())
                .cgstAmount(i.getCgstAmount()).sgstAmount(i.getSgstAmount()).igstAmount(i.getIgstAmount())
                .gstTotal(i.getGstTotal()).tcsRatePercent(i.getTcsRatePercent()).tcsSection(i.getTcsSection())
                .tcsBaseAmount(i.getTcsBaseAmount()).tcsAmount(i.getTcsAmount()).roundOff(i.getRoundOff())
                .grandTotal(i.getGrandTotal()).taxableValueInr(i.getTaxableValueInr()).gstTotalInr(i.getGstTotalInr())
                .tcsAmountInr(i.getTcsAmountInr()).grandTotalInr(i.getGrandTotalInr())
                .amountReceived(i.getAmountReceived()).creditNoteTotal(i.getCreditNoteTotal())
                .balanceDue(i.getBalanceDue()).balanceDueInr(i.getBalanceDueInr())
                .invoiceDate(i.getInvoiceDate()).dueDate(i.getDueDate())
                .issuedAt(i.getIssuedAt()).issuedBy(i.getIssuedBy())
                .cancelledAt(i.getCancelledAt()).cancelledBy(i.getCancelledBy()).cancelReason(i.getCancelReason())
                .supersedesInvoiceId(i.getSupersedesInvoiceId()).notes(i.getNotes()).terms(i.getTerms())
                .lines(lines.stream().map(InvoiceDocumentService::toLineResponse).toList())
                .build();
    }

    private static InvoiceLineItemResponse toLineResponse(InvoiceLineItem l) {
        return InvoiceLineItemResponse.builder()
                .id(l.getId()).sortOrder(l.getSortOrder()).description(l.getDescription())
                .sacCode(l.getSacCode()).serviceType(l.getServiceType())
                .quantity(l.getQuantity()).unitPrice(l.getUnitPrice()).lineSubtotal(l.getLineSubtotal())
                .discountAmount(l.getDiscountAmount()).taxablePercent(l.getTaxablePercent())
                .taxableValue(l.getTaxableValue()).gstRatePercent(l.getGstRatePercent())
                .cgstRatePercent(l.getCgstRatePercent()).sgstRatePercent(l.getSgstRatePercent())
                .igstRatePercent(l.getIgstRatePercent()).cgstAmount(l.getCgstAmount())
                .sgstAmount(l.getSgstAmount()).igstAmount(l.getIgstAmount()).lineTotal(l.getLineTotal())
                .build();
    }

    private static InvoiceListItemResponse toListItem(Invoice i) {
        return InvoiceListItemResponse.builder()
                .id(i.getId()).invoiceNumber(i.getInvoiceNumber()).financialYear(i.getFinancialYear())
                .documentType(i.getDocumentType()).status(i.getStatus()).serviceCategory(i.getServiceCategory())
                .clientId(i.getClientId()).clientName(i.getClientName())
                .bookingId(i.getBookingId()).agentId(i.getAgentId())
                .currencyCode(i.getCurrencyCode()).grandTotal(i.getGrandTotal()).grandTotalInr(i.getGrandTotalInr())
                .balanceDue(i.getBalanceDue()).balanceDueInr(i.getBalanceDueInr())
                .invoiceDate(i.getInvoiceDate()).dueDate(i.getDueDate()).issuedAt(i.getIssuedAt())
                .build();
    }
}
