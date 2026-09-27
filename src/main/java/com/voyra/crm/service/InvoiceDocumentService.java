package com.voyra.crm.service;

import com.voyra.crm.dto.AuditChange;
import com.voyra.crm.dto.InvoiceDraftRequest;
import com.voyra.crm.dto.InvoiceLineItemRequest;
import com.voyra.crm.dto.InvoiceLineItemResponse;
import com.voyra.crm.dto.InvoiceListItemResponse;
import com.voyra.crm.dto.InvoiceResponse;
import com.voyra.crm.dto.InvoiceTaxRequest;
import com.voyra.crm.dto.InvoiceTaxResponse;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.BookingPassenger;
import com.voyra.crm.entity.BookingSector;
import com.voyra.crm.entity.Client;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.entity.InvoiceLineItem;
import com.voyra.crm.entity.InvoiceTax;
import com.voyra.crm.entity.PaymentReceipt;
import com.voyra.crm.entity.TaxRateConfig;
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
import com.voyra.crm.enums.TaxKind;
import com.voyra.crm.enums.TaxLineMode;
import com.voyra.crm.enums.TaxTreatment;
import com.voyra.crm.models.LedgerPosting;
import com.voyra.crm.repository.BookingPassengerRepository;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.BookingSectorRepository;
import com.voyra.crm.repository.InvoiceLineItemRepository;
import com.voyra.crm.repository.InvoiceRepository;
import com.voyra.crm.repository.InvoiceTaxRepository;
import com.voyra.crm.repository.PaymentReceiptRepository;
import com.voyra.crm.repository.TaxRateConfigRepository;
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
    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final InvoiceRepository invoiceRepository;
    private final InvoiceLineItemRepository invoiceLineItemRepository;
    private final InvoiceTaxRepository invoiceTaxRepository;
    private final PaymentReceiptRepository paymentReceiptRepository;
    private final BookingRepository bookingRepository;
    private final BookingPassengerRepository bookingPassengerRepository;
    private final BookingSectorRepository bookingSectorRepository;
    private final ClientService clientService;
    private final TenantRepository tenantRepository;
    private final TaxRateConfigRepository taxRateConfigRepository;
    private final TaxEngine taxEngine;
    private final DocumentNumberService documentNumberService;
    private final AuditService auditService;
    private final CustomerLedgerService customerLedgerService;
    private final BookingAccountingSync bookingAccountingSync;
    private final PaymentReceiptService paymentReceiptService;

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
        // An explicit request wins outright - the only way to reach RAIL or MISCELLANEOUS,
        // which have no BookingType to derive from (spec gap 8).
        InvoiceServiceCategory category = request.getServiceCategory() != null
                ? request.getServiceCategory()
                : InvoiceServiceCategory.forBooking(booking.getType(), Boolean.TRUE.equals(booking.getInternationalTrip()));

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
                .leadId(booking.getLeadId())
                .agentId(booking.getAgentId())
                .createdAt(LocalDateTime.now())
                .createdBy(currentUserId())
                .build();

        applyDraftFields(invoice, request);
        // ACCOUNTING_REDESIGN_SPEC.md gap 7 - a new draft that didn't specify its own terms
        // gets the agency's standing default, so a non-accounts operator never has to know
        // there's a terms field to fill in at all.
        if ((invoice.getTerms() == null || invoice.getTerms().isBlank())
                && agency.getInvoiceTerms() != null && !agency.getInvoiceTerms().isBlank()) {
            invoice.setTerms(agency.getInvoiceTerms());
        }
        List<InvoiceLineItemRequest> lineInputs = resolveLines(booking, category, request.getLines());
        List<InvoiceLineItem> lines = recomputeLines(invoice, lineInputs);
        List<InvoiceTax> taxes = recomputeTaxes(invoice, request.getTaxes());
        invoiceRepository.save(invoice);
        invoiceLineItemRepository.saveAll(lines);
        invoiceTaxRepository.saveAll(taxes);

        log.info("Invoice draft created: id={}, bookingId={}", invoice.getId(), booking.getId());
        return toResponse(invoice, lines, taxes);
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
        List<InvoiceLineItem> lines = recomputeLines(invoice, lineInputs);
        List<InvoiceTax> taxes = recomputeTaxes(invoice, request.getTaxes());
        invoiceRepository.save(invoice);
        invoiceLineItemRepository.deleteByInvoiceId(id);
        invoiceLineItemRepository.saveAll(lines);
        invoiceTaxRepository.deleteByInvoiceId(id);
        invoiceTaxRepository.saveAll(taxes);

        log.info("Invoice draft updated: id={}", id);
        return toResponse(invoice, lines, taxes);
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
        invoiceTaxRepository.deleteByInvoiceId(id);
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
        List<InvoiceTax> taxes = invoiceTaxRepository.findByInvoiceIdOrderBySortOrderAsc(id);
        // Bank details and the booking's own reference (PNR, confirmation no, ...) are read
        // fresh at print time, never frozen on the invoice - they're presentational only and
        // never feed a money figure, unlike everything InvoicePdfRenderer otherwise reads.
        Tenant agency = currentAgency();
        Booking booking = invoice.getBookingId() != null
                ? bookingRepository.findById(invoice.getBookingId()).orElse(null) : null;
        return InvoicePdfRenderer.write(invoice, lines, taxes, agency, booking);
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
        // A returning customer's standing deposit draws down automatically the moment a real
        // invoice exists against them - see PaymentReceiptService#applyAvailableWallet.
        paymentReceiptService.applyAvailableWallet(invoice);
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
        List<InvoiceTax> proformaTaxes = invoiceTaxRepository.findByInvoiceIdOrderBySortOrderAsc(proformaId);
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

        List<InvoiceTax> newTaxes = new ArrayList<>();
        for (InvoiceTax tax : proformaTaxes) {
            newTaxes.add(tax.toBuilder()
                    .id(UniqueIdResolver.resolve(invoiceTaxRepository::existsById))
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
        invoiceTaxRepository.saveAll(newTaxes);
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
        return toResponse(taxInvoice, newLines, newTaxes);
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
     * Lines carry no tax any more - see {@link #recomputeTaxes}. A line is purely the booking's
     * own billable amount (quantity x unit price, less any discount); {@code taxableValue}
     * mirrors that net figure so a pre-redesign report reading the column still sees a sane
     * number, and every GST-rate column stays zero.
     */
    private List<InvoiceLineItem> recomputeLines(Invoice invoice, List<InvoiceLineItemRequest> lineRequests) {
        List<InvoiceLineItem> lines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal discountTotal = BigDecimal.ZERO;

        if (lineRequests != null) {
            int sortOrder = 0;
            for (InvoiceLineItemRequest req : lineRequests) {
                BigDecimal discount = req.getDiscountAmount() != null ? req.getDiscountAmount() : BigDecimal.ZERO;
                BigDecimal lineSubtotal = req.getQuantity().multiply(req.getUnitPrice()).setScale(2, RoundingMode.HALF_UP);
                BigDecimal net = lineSubtotal.subtract(discount);

                InvoiceLineItem line = InvoiceLineItem.builder()
                        .id(UniqueIdResolver.resolve(invoiceLineItemRepository::existsById))
                        .invoiceId(invoice.getId())
                        .sortOrder(sortOrder++)
                        .description(req.getDescription())
                        .sacCode(req.getSacCode())
                        .serviceType(req.getServiceType())
                        .quantity(req.getQuantity())
                        .unitPrice(req.getUnitPrice())
                        .lineSubtotal(lineSubtotal)
                        .discountAmount(discount)
                        .taxablePercent(new BigDecimal("100.000"))
                        .taxableValue(net)
                        .lineTotal(net)
                        .fareAmount(req.getFareAmount())
                        .taxAmount(req.getTaxAmount())
                        .createdAt(LocalDateTime.now())
                        .build();
                lines.add(line);

                subtotal = subtotal.add(lineSubtotal);
                discountTotal = discountTotal.add(discount);
            }
        }

        BigDecimal taxableValue = subtotal.subtract(discountTotal);
        invoice.setSubtotal(subtotal);
        invoice.setDiscountTotal(discountTotal);
        invoice.setTaxableValue(taxableValue);
        return lines;
    }

    /**
     * Tax is opt-in per invoice: zero requested rows means zero tax, full stop - nothing here
     * ever falls back to a configured default. Each row's amount is derived either from the
     * chosen {@code tax_rate_config}'s own rate or from whatever the accountant typed for a
     * custom tax; a GST-kind row additionally gets the CGST+SGST vs IGST split from {@link
     * TaxEngine#resolveTreatment}. {@code visibleToCustomer} never changes {@code amount} - it
     * only tells {@code InvoicePdfRenderer} whether to print this row or fold it into the fare.
     */
    private List<InvoiceTax> recomputeTaxes(Invoice invoice, List<InvoiceTaxRequest> taxRequests) {
        List<InvoiceTax> taxes = new ArrayList<>();
        BigDecimal taxableBase = invoice.getTaxableValue();
        BigDecimal cgst = BigDecimal.ZERO;
        BigDecimal sgst = BigDecimal.ZERO;
        BigDecimal igst = BigDecimal.ZERO;
        BigDecimal tcsAmount = BigDecimal.ZERO;
        BigDecimal tcsRate = BigDecimal.ZERO;
        String tcsSection = null;
        BigDecimal otherTaxTotal = BigDecimal.ZERO;
        TaxTreatment treatment = null;
        String placeOfSupply = null;

        if (taxRequests != null) {
            int sortOrder = 0;
            for (InvoiceTaxRequest req : taxRequests) {
                TaxRateConfig config = req.getTaxRateConfigId() != null && !req.getTaxRateConfigId().isBlank()
                        ? taxRateConfigRepository.findById(req.getTaxRateConfigId())
                            .orElseThrow(() -> new IllegalArgumentException("Tax rate config not found: " + req.getTaxRateConfigId()))
                        : null;
                TaxKind kind = config != null ? config.getTaxKind() : null;

                BigDecimal amount = req.getMode() == TaxLineMode.FLAT
                        ? (req.getFlatAmount() != null ? req.getFlatAmount() : BigDecimal.ZERO)
                        : pct(taxableBase, req.getRatePercent() != null ? req.getRatePercent() : BigDecimal.ZERO);

                BigDecimal rowCgst = BigDecimal.ZERO;
                BigDecimal rowSgst = BigDecimal.ZERO;
                BigDecimal rowIgst = BigDecimal.ZERO;
                if (kind == TaxKind.GST) {
                    treatment = taxEngine.resolveTreatment(invoice.getClientId(), invoice.getPlaceOfSupplyOverride(),
                            Boolean.TRUE.equals(invoice.getExportOfServiceRequested()), invoice.getCurrencyCode());
                    placeOfSupply = taxEngine.resolvePlaceOfSupplyCode(invoice.getClientId(), invoice.getPlaceOfSupplyOverride());
                    if (treatment == TaxTreatment.INTRA_STATE) {
                        rowCgst = amount.divide(TWO, 2, RoundingMode.HALF_UP);
                        rowSgst = amount.subtract(rowCgst);
                    } else {
                        rowIgst = amount;
                    }
                    cgst = cgst.add(rowCgst);
                    sgst = sgst.add(rowSgst);
                    igst = igst.add(rowIgst);
                } else if (kind == TaxKind.TCS) {
                    tcsAmount = tcsAmount.add(amount);
                    tcsRate = req.getRatePercent() != null ? req.getRatePercent() : tcsRate;
                    tcsSection = config.getTcsSection();
                } else {
                    otherTaxTotal = otherTaxTotal.add(amount);
                }

                InvoiceTax tax = InvoiceTax.builder()
                        .id(UniqueIdResolver.resolve(invoiceTaxRepository::existsById))
                        .invoiceId(invoice.getId())
                        .sortOrder(sortOrder++)
                        .label(req.getLabel())
                        .taxRateConfigId(config != null ? config.getId() : null)
                        .taxKind(kind)
                        .mode(req.getMode())
                        .ratePercent(req.getRatePercent())
                        .flatAmount(req.getFlatAmount())
                        .cgstAmount(rowCgst)
                        .sgstAmount(rowSgst)
                        .igstAmount(rowIgst)
                        .amount(amount)
                        .visibleToCustomer(req.getVisibleToCustomer() == null || req.getVisibleToCustomer())
                        .createdAt(LocalDateTime.now())
                        .build();
                taxes.add(tax);
            }
        }

        BigDecimal gstTotal = cgst.add(sgst).add(igst);
        BigDecimal grandTotal = taxableBase.add(gstTotal).add(tcsAmount).add(otherTaxTotal);
        BigDecimal fx = invoice.getFxRateToInr();

        invoice.setPlaceOfSupplyCode(placeOfSupply);
        invoice.setTaxTreatment(treatment);
        invoice.setCgstAmount(cgst);
        invoice.setSgstAmount(sgst);
        invoice.setIgstAmount(igst);
        invoice.setGstTotal(gstTotal);
        invoice.setTcsRatePercent(tcsRate);
        invoice.setTcsSection(tcsSection);
        invoice.setTcsBaseAmount(tcsAmount.compareTo(BigDecimal.ZERO) > 0 ? taxableBase : BigDecimal.ZERO);
        invoice.setTcsAmount(tcsAmount);
        invoice.setOtherTaxTotal(otherTaxTotal);
        invoice.setOtherTaxTotalInr(scaleToInr(otherTaxTotal, fx));
        invoice.setRoundOff(BigDecimal.ZERO);
        invoice.setGrandTotal(grandTotal);
        invoice.setTaxableValueInr(scaleToInr(taxableBase, fx));
        invoice.setGstTotalInr(scaleToInr(gstTotal, fx));
        invoice.setTcsAmountInr(scaleToInr(tcsAmount, fx));
        invoice.setGrandTotalInr(scaleToInr(grandTotal, fx));
        invoice.setBalanceDue(grandTotal.subtract(invoice.getAmountReceived()).subtract(invoice.getCreditNoteTotal()));
        invoice.setBalanceDueInr(scaleToInr(invoice.getBalanceDue(), fx));

        return taxes;
    }

    private static BigDecimal pct(BigDecimal base, BigDecimal ratePercent) {
        return base.multiply(ratePercent).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal scaleToInr(BigDecimal amount, BigDecimal fxRateToInr) {
        return amount.multiply(fxRateToInr).setScale(2, RoundingMode.HALF_UP);
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

    private InvoiceResponse toResponse(Invoice invoice, List<InvoiceLineItem> lines) {
        return toResponse(invoice, lines, invoiceTaxRepository.findByInvoiceIdOrderBySortOrderAsc(invoice.getId()));
    }

    private InvoiceResponse toResponse(Invoice i, List<InvoiceLineItem> lines, List<InvoiceTax> taxes) {
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
                .tcsBaseAmount(i.getTcsBaseAmount()).tcsAmount(i.getTcsAmount())
                .otherTaxTotal(i.getOtherTaxTotal()).otherTaxTotalInr(i.getOtherTaxTotalInr()).roundOff(i.getRoundOff())
                .grandTotal(i.getGrandTotal()).taxableValueInr(i.getTaxableValueInr()).gstTotalInr(i.getGstTotalInr())
                .tcsAmountInr(i.getTcsAmountInr()).grandTotalInr(i.getGrandTotalInr())
                .amountReceived(i.getAmountReceived()).creditNoteTotal(i.getCreditNoteTotal())
                .balanceDue(i.getBalanceDue()).balanceDueInr(i.getBalanceDueInr())
                .invoiceDate(i.getInvoiceDate()).dueDate(i.getDueDate())
                .issuedAt(i.getIssuedAt()).issuedBy(i.getIssuedBy())
                .cancelledAt(i.getCancelledAt()).cancelledBy(i.getCancelledBy()).cancelReason(i.getCancelReason())
                .supersedesInvoiceId(i.getSupersedesInvoiceId()).notes(i.getNotes()).terms(i.getTerms())
                .lines(lines.stream().map(InvoiceDocumentService::toLineResponse).toList())
                .taxes(taxes.stream().map(InvoiceDocumentService::toTaxResponse).toList())
                .build();
    }

    private static InvoiceTaxResponse toTaxResponse(InvoiceTax t) {
        return InvoiceTaxResponse.builder()
                .id(t.getId()).label(t.getLabel()).taxRateConfigId(t.getTaxRateConfigId()).taxKind(t.getTaxKind())
                .mode(t.getMode()).ratePercent(t.getRatePercent()).flatAmount(t.getFlatAmount())
                .cgstAmount(t.getCgstAmount()).sgstAmount(t.getSgstAmount()).igstAmount(t.getIgstAmount())
                .amount(t.getAmount()).visibleToCustomer(t.getVisibleToCustomer())
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
                .fareAmount(l.getFareAmount()).taxAmount(l.getTaxAmount())
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
