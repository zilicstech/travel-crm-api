package com.voyra.crm.service;

import com.voyra.crm.dto.AuditChange;
import com.voyra.crm.dto.InvoiceBookingRefResponse;
import com.voyra.crm.dto.InvoiceDraftRequest;
import com.voyra.crm.dto.InvoiceLineItemRequest;
import com.voyra.crm.dto.InvoiceLineItemResponse;
import com.voyra.crm.dto.InvoiceListItemResponse;
import com.voyra.crm.dto.InvoiceResponse;
import com.voyra.crm.dto.InvoiceTaxRequest;
import com.voyra.crm.dto.InvoiceTaxResponse;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.BookingCostComponent;
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
import com.voyra.crm.enums.InvoiceBillingModel;
import com.voyra.crm.enums.InvoiceDocumentType;
import com.voyra.crm.enums.InvoiceLifecycle;
import com.voyra.crm.enums.InvoiceServiceCategory;
import com.voyra.crm.enums.LedgerEntryType;
import com.voyra.crm.enums.LedgerSourceType;
import com.voyra.crm.enums.MarkupMode;
import com.voyra.crm.enums.TaxKind;
import com.voyra.crm.enums.TaxLineMode;
import com.voyra.crm.enums.TaxTreatment;
import com.voyra.crm.models.LedgerPosting;
import com.voyra.crm.repository.BookingCostComponentRepository;
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
import com.voyra.crm.util.AirInvoicePdfRenderer;
import com.voyra.crm.util.InvoicePdfRenderer;
import com.voyra.crm.util.InvoiceShares;
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
    private final BookingCostComponentRepository bookingCostComponentRepository;
    private final JournalService journalService;
    private final com.voyra.crm.repository.JournalEntryRepository journalEntryRepository;
    private final com.voyra.crm.repository.JournalLineRepository journalLineRepository;
    private final ClientService clientService;
    private final TenantRepository tenantRepository;
    private final TaxRateConfigRepository taxRateConfigRepository;
    private final TaxEngine taxEngine;
    private final DocumentNumberService documentNumberService;
    private final AuditService auditService;
    private final CustomerLedgerService customerLedgerService;
    private final BookingAccountingSync bookingAccountingSync;
    private final PaymentReceiptService paymentReceiptService;
    private final FileStorageService fileStorageService;
    private final BaseCurrency baseCurrency;
    private final InvoiceLineSource invoiceLineSource;
    private final com.voyra.crm.repository.BookingDocumentRepository bookingDocumentRepository;
    private final com.voyra.crm.repository.AgentRepository agentRepository;

    /**
     * One draft invoice for several bookings of one client - the accountant's bulk "create draft
     * invoice". They must share the supplier, the agent and the invoice type (its series, title and
     * GST nature), be confirmed, have something left to bill and sit on no other draft. One line per
     * voucher not yet invoiced; GST on the markup only (the vouchers' cost passes through untaxed).
     * The bookings are locked in id order first, so two accountants drafting overlapping bookings
     * queue rather than both succeeding.
     */
    @Transactional
    public InvoiceResponse createDrafts(List<String> bookingIds) {
        List<String> ids = bookingIds == null ? List.of() : bookingIds.stream()
                .filter(id -> id != null && !id.isBlank()).distinct().sorted().toList();
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("Pick at least one booking");
        }
        List<Booking> bookings = ids.stream().map(id -> bookingRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + id)))
                .sorted(java.util.Comparator.comparing(Booking::getBookingNumber, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .toList();
        Booking first = bookings.get(0);
        InvoiceServiceCategory category = categoryOf(first);
        List<InvoiceLineItemRequest> lines = new ArrayList<>();
        BigDecimal passThrough = BigDecimal.ZERO;
        for (Booking b : bookings) {
            String ref = BookingService.bookingRef(b.getBookingNumber());
            if (b.getBookingStatus() != BookingStatus.CONFIRMED && b.getBookingStatus() != BookingStatus.COMPLETED) {
                throw new IllegalStateException(ref + (b.getBookingStatus() == BookingStatus.CANCELLED
                        ? " is cancelled - it can't be invoiced" : " isn't confirmed yet - add its priced voucher first"));
            }
            // Combining is by supplier; a booking invoiced on its own needs none.
            if (bookings.size() > 1 && (b.getVendorId() == null || b.getVendorId().isBlank())) {
                throw new IllegalStateException(ref + " has no supplier - set it on the booking before combining it");
            }
            if (!b.getClientId().equals(first.getClientId()) || !java.util.Objects.equals(b.getVendorId(), first.getVendorId())
                    || !java.util.Objects.equals(b.getAgentId(), first.getAgentId()) || categoryOf(b) != category) {
                throw new IllegalArgumentException("One invoice takes bookings of the same client, supplier, agent and invoice type only - "
                        + ref + " differs from " + BookingService.bookingRef(first.getBookingNumber()));
            }
            if (invoiceLineSource.draftOf(b.getId()) != null) {
                throw new IllegalStateException(ref + " is already on a draft invoice - open that draft instead");
            }
            InvoiceLineSource.Lines own = invoiceLineSource.linesFor(b, category);
            if (own.lines().isEmpty()) {
                throw new IllegalStateException(ref + " is fully invoiced");
            }
            lines.addAll(own.lines());
            passThrough = passThrough.add(own.passThrough());
        }
        InvoiceDraftRequest draft = new InvoiceDraftRequest();
        draft.setBookingId(first.getId());
        draft.setServiceCategory(category);
        draft.setBillingModel(InvoiceBillingModel.COMMISSION_AGENT);
        draft.setPassThroughAmount(passThrough);
        draft.setSupplyNature(BookingInvoiceIssuer.supplyNature(category, Boolean.TRUE.equals(first.getInternationalTrip())));
        draft.setLines(lines);
        taxRateConfigRepository.findByTaxKindAndSupplyNatureAndIsDefaultTrueAndIsActiveTrue(TaxKind.GST, draft.getSupplyNature())
                .filter(gst -> baseCurrency.code().equals("INR"))
                .ifPresent(gst -> {
                    InvoiceTaxRequest tax = new InvoiceTaxRequest();
                    tax.setLabel(gst.getLabel());
                    tax.setTaxRateConfigId(gst.getId());
                    tax.setMode(TaxLineMode.PERCENT);
                    tax.setRatePercent(gst.getRatePercent());
                    tax.setVisibleToCustomer(true);
                    draft.setTaxes(List.of(tax));
                });
        InvoiceResponse created = draftFrom(draft);
        log.info("Draft invoice created for bookings: id={}, bookings={}", created.getId(), ids);
        return created;
    }

    private static InvoiceServiceCategory categoryOf(Booking b) {
        return InvoiceServiceCategory.forBooking(b.getType(), Boolean.TRUE.equals(b.getInternationalTrip()));
    }

    /**
     * Takes a booking off its live draft (nothing is posted on a draft): the draft is deleted when it
     * bills only that booking, else rebuilt for the rest. Used before the booking is cancelled or gets
     * an invoice uploaded, so a colleague's combined draft keeps its other bookings.
     */
    @Transactional
    public void removeBookingFromDraft(String bookingId) {
        for (Invoice draft : invoiceRepository.findDraftsForBooking(bookingId)) {
            List<String> rest = invoiceLineSource.bookingIdsOf(draft).stream().filter(id -> !id.equals(bookingId)).toList();
            if (rest.isEmpty()) {
                deleteDraft(draft.getId());
                continue;
            }
            draft.setBookingId(rest.get(0));
            Booking primary = bookingRepository.findById(rest.get(0)).orElseThrow();
            draft.setLeadId(primary.getLeadId());
            List<InvoiceLineItemRequest> lineInputs = rederive(draft, rest, null);
            List<InvoiceLineItem> lines = recomputeLines(draft, lineInputs);
            List<InvoiceTax> taxes = recomputeTaxes(draft, taxRequestsOf(draft.getId()));
            invoiceRepository.save(draft);
            invoiceLineItemRepository.deleteByInvoiceId(draft.getId());
            invoiceLineItemRepository.saveAll(lines);
            invoiceTaxRepository.deleteByInvoiceId(draft.getId());
            invoiceTaxRepository.saveAll(taxes);
            log.info("Booking taken off draft invoice: bookingId={}, invoiceId={}", bookingId, draft.getId());
        }
    }

    /** This booking's weight on an invoice: 1 when it bills only this booking. */
    public BigDecimal weightOf(Invoice invoice, String bookingId) {
        java.util.Map<String, InvoiceShares.Share> shares = sharesOf(invoice);
        InvoiceShares.Share share = shares.get(bookingId);
        return share != null ? share.weight() : shares.isEmpty() ? BigDecimal.ONE : BigDecimal.ZERO;
    }


    private java.util.Map<String, InvoiceShares.Share> sharesOf(Invoice invoice) {
        return InvoiceShares.of(invoice.getBookingId(), invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc(invoice.getId()));
    }

    @Transactional
    public InvoiceResponse createDraft(InvoiceDraftRequest request) {
        // Charges print on the tax invoice: only the server sets them, from a voucher (createDrafts).
        if (request.getLines() != null) request.getLines().forEach(l -> {
            l.setCharges(null);
            l.setTravellers(null);
            l.setLegs(null);
            l.setPurchaseCurrency(null);
            l.setPurchaseRate(null);
        });
        return draftFrom(request);
    }

    private InvoiceResponse draftFrom(InvoiceDraftRequest request) {
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
                .billingModel(request.getBillingModel() != null ? request.getBillingModel() : category.defaultBillingModel())
                .passThroughAmount(request.getPassThroughAmount())
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
        List<InvoiceLineItemRequest> lineInputs = resolveLines(booking, category, request.getLines(), invoice);
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
        if (request.getBillingModel() != null) {
            invoice.setBillingModel(request.getBillingModel());
        } else if (invoice.getBillingModel() == null) {
            invoice.setBillingModel(category.defaultBillingModel());
        }

        applyDraftFields(invoice, request);
        invoice.setUpdatedAt(LocalDateTime.now());
        invoice.setUpdatedBy(currentUserId());

        // Prices always come from the vouchers; only the lines' wording is taken from the request.
        List<InvoiceLineItemRequest> lineInputs = rederive(invoice, invoiceLineSource.bookingIdsOf(invoice), request.getLines());
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
     * The caller's lines when it gives any (an uploaded invoice, cancellation charges), each billing
     * this booking; otherwise what the booking has left to bill, from its vouchers - see
     * {@link InvoiceLineSource#linesFor}. A booking with nothing left falls back to its travellers'
     * lines, as before vouchers carried the money.
     */
    private List<InvoiceLineItemRequest> resolveLines(Booking booking, InvoiceServiceCategory category,
                                                        List<InvoiceLineItemRequest> requestedLines, Invoice invoice) {
        if (requestedLines != null && !requestedLines.isEmpty()) {
            requestedLines.stream().filter(l -> l.getBookingId() == null).forEach(l -> l.setBookingId(booking.getId()));
            return requestedLines;
        }
        InvoiceLineSource.Lines own = invoiceLineSource.linesFor(booking, category);
        if (!own.lines().isEmpty()) {
            if (invoice.getPassThroughAmount() == null) {
                invoice.setPassThroughAmount(inInvoiceCurrency(invoice, own.passThrough()));
            }
            return inInvoiceCurrency(invoice, own.lines());
        }
        List<BookingPassenger> passengers = bookingPassengerRepository.findByBookingIdOrderBySortOrderAsc(booking.getId());
        Map<String, List<BookingSector>> sectorsByPassenger = passengers.isEmpty() ? Map.of()
                : bookingSectorRepository.findByBookingPassengerIdInOrderBySortOrderAsc(
                        passengers.stream().map(BookingPassenger::getId).toList())
                    .stream().collect(java.util.stream.Collectors.groupingBy(BookingSector::getBookingPassengerId));
        List<InvoiceLineItemRequest> lines = BookingInvoiceLineBuilder.build(booking, category, passengers, sectorsByPassenger);
        lines.forEach(l -> l.setBookingId(booking.getId()));
        return lines;
    }

    /**
     * A draft's lines worked out again from its bookings' vouchers, keeping any wording the accountant
     * gave a line (matched by voucher, else by booking, in order). A booking with nothing left to bill
     * keeps the lines it had. Sets the pass-through to the vouchers' cost.
     */
    private List<InvoiceLineItemRequest> rederive(Invoice invoice, List<String> bookingIds, List<InvoiceLineItemRequest> edits) {
        InvoiceServiceCategory category = invoice.getServiceCategory();
        List<InvoiceLineItem> existing = invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc(invoice.getId());
        java.util.Map<String, java.util.ArrayDeque<String>> wording = new java.util.HashMap<>();
        for (InvoiceLineItemRequest e : edits != null ? edits : existing.stream().map(InvoiceDocumentService::toRequest).toList()) {
            if (e.getDescription() != null && !e.getDescription().isBlank()) {
                wording.computeIfAbsent(lineKey(e), k -> new java.util.ArrayDeque<>()).add(e.getDescription().trim());
            }
        }
        List<InvoiceLineItemRequest> out = new ArrayList<>();
        BigDecimal passThrough = BigDecimal.ZERO;
        for (String id : bookingIds) {
            Booking b = bookingRepository.findById(id).orElseThrow(() -> new IllegalStateException("Booking not found: " + id));
            InvoiceLineSource.Lines own = invoiceLineSource.linesFor(b, category != null ? category : categoryOf(b));
            if (own.lines().isEmpty()) {
                existing.stream().filter(l -> id.equals(l.getBookingId() != null ? l.getBookingId() : invoice.getBookingId()))
                        .map(InvoiceDocumentService::toRequest).forEach(out::add);
                continue;
            }
            out.addAll(inInvoiceCurrency(invoice, own.lines()));
            passThrough = passThrough.add(inInvoiceCurrency(invoice, own.passThrough()));
        }
        for (InvoiceLineItemRequest line : out) {
            java.util.ArrayDeque<String> words = wording.get(lineKey(line));
            if (words != null && !words.isEmpty()) line.setDescription(words.poll());
        }
        invoice.setPassThroughAmount(passThrough.signum() > 0 ? passThrough : invoice.getPassThroughAmount());
        return out;
    }

    /**
     * The vouchers' prices are in base; an invoice billed in another currency carries them at its own rate
     * (the accountant's, on the draft). Every save works them out again from base, so changing the currency
     * or the rate never compounds a rounding.
     */
    private List<InvoiceLineItemRequest> inInvoiceCurrency(Invoice invoice, List<InvoiceLineItemRequest> lines) {
        BigDecimal fx = foreignRate(invoice);
        if (fx == null) return lines;
        for (InvoiceLineItemRequest l : lines) {
            l.setUnitPrice(l.getUnitPrice().divide(fx, 2, RoundingMode.HALF_UP));
            if (l.getCharges() != null) {
                Map<String, BigDecimal> converted = new java.util.LinkedHashMap<>();
                l.getCharges().forEach((k, v) -> converted.put(k, v == null ? null : v.divide(fx, 2, RoundingMode.HALF_UP)));
                l.setCharges(converted);
            }
        }
        return lines;
    }

    private BigDecimal inInvoiceCurrency(Invoice invoice, BigDecimal baseAmount) {
        BigDecimal fx = foreignRate(invoice);
        return fx == null || baseAmount == null ? baseAmount : baseAmount.divide(fx, 2, RoundingMode.HALF_UP);
    }

    /** The invoice's rate to base when it is billed in another currency; null when it is in base. */
    private BigDecimal foreignRate(Invoice invoice) {
        return baseCurrency.code().equals(invoice.getCurrencyCode()) || invoice.getFxRateToInr() == null
                || invoice.getFxRateToInr().compareTo(BigDecimal.ONE) == 0 ? null : invoice.getFxRateToInr();
    }

    private static String lineKey(InvoiceLineItemRequest l) {
        return l.getBookingDocumentId() != null ? "v:" + l.getBookingDocumentId() : "b:" + l.getBookingId();
    }

    private static InvoiceLineItemRequest toRequest(InvoiceLineItem l) {
        InvoiceLineItemRequest r = new InvoiceLineItemRequest();
        r.setDescription(l.getDescription());
        r.setSacCode(l.getSacCode());
        r.setServiceType(l.getServiceType());
        r.setQuantity(l.getQuantity());
        r.setUnitPrice(l.getUnitPrice());
        r.setDiscountAmount(l.getDiscountAmount());
        r.setFareAmount(l.getFareAmount());
        r.setTaxAmount(l.getTaxAmount());
        r.setBookingId(l.getBookingId());
        r.setBookingDocumentId(l.getBookingDocumentId());
        r.setCharges(l.getCharges());
        r.setTravellers(l.getTravellers());
        r.setLegs(l.getLegs());
        r.setPurchaseCurrency(l.getPurchaseCurrency());
        r.setPurchaseRate(l.getPurchaseRate());
        return r;
    }

    private List<InvoiceTaxRequest> taxRequestsOf(String invoiceId) {
        return invoiceTaxRepository.findByInvoiceIdOrderBySortOrderAsc(invoiceId).stream().map(t -> {
            InvoiceTaxRequest r = new InvoiceTaxRequest();
            r.setLabel(t.getLabel());
            r.setTaxRateConfigId(t.getTaxRateConfigId());
            r.setMode(t.getMode());
            r.setRatePercent(t.getRatePercent());
            r.setFlatAmount(t.getFlatAmount());
            r.setVisibleToCustomer(t.getVisibleToCustomer());
            return r;
        }).toList();
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

    /** An uploaded invoice's file. Not {@code @Transactional}: reading object storage is barred inside a transaction (blueprint §8.6). */
    public UploadedInvoiceFile getUploadedFile(String id) {
        Invoice invoice = findAccessibleInvoice(id);
        if (invoice.getPdfFileKey() == null) {
            throw new IllegalArgumentException("This invoice has no uploaded file");
        }
        return new UploadedInvoiceFile(fileStorageService.retrieve(invoice.getPdfFileKey()),
                invoice.getUploadedFileName(), invoice.getUploadedContentType());
    }

    /** Not a DTO - never serialized to JSON, only unpacked into a binary response by the controller. */
    public record UploadedInvoiceFile(org.springframework.core.io.Resource resource, String filename, String contentType) {
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
        boolean air = AirInvoicePdfRenderer.applies(invoice.getServiceCategory(), lines);
        if (air || AirInvoicePdfRenderer.appliesToHotel(invoice.getServiceCategory(), lines)) {
            Map<String, String> references = new java.util.HashMap<>();
            Map<String, Map<String, String>> details = new java.util.HashMap<>();
            bookingDocumentRepository.findAllById(lines.stream().map(InvoiceLineItem::getBookingDocumentId)
                            .filter(java.util.Objects::nonNull).distinct().toList())
                    .forEach(d -> {
                        references.put(d.getId(), d.getReferenceNumber());
                        if (d.getDetails() != null) details.put(d.getId(), d.getDetails());
                    });
            String preparedBy = userName(invoice.getIssuedBy() != null ? invoice.getIssuedBy() : invoice.getCreatedBy());
            return air
                    ? AirInvoicePdfRenderer.write(invoice, lines, taxes, agency, references, logoOf(agency), preparedBy)
                    : AirInvoicePdfRenderer.writeHotel(invoice, lines, taxes, agency, references, details, logoOf(agency), preparedBy);
        }
        Booking booking = invoice.getBookingId() != null
                ? bookingRepository.findById(invoice.getBookingId()).orElse(null) : null;
        List<String> bookingIds = invoiceLineSource.bookingIdsOf(invoice);
        // Cost components belong to one booking; an invoice of several prints the cost it passes through.
        List<BookingCostComponent> costComponents = booking != null && bookingIds.size() <= 1
                ? bookingCostComponentRepository.findByBookingIdOrderBySortOrder(booking.getId()) : List.of();
        List<String> voucherRefs = bookingDocumentRepository.findAllById(lines.stream().map(InvoiceLineItem::getBookingDocumentId)
                        .filter(java.util.Objects::nonNull).distinct().toList()).stream()
                .map(com.voyra.crm.entity.BookingDocument::getReferenceNumber).filter(r -> r != null && !r.isBlank())
                .distinct().toList();
        Map<String, String> refs = new java.util.HashMap<>();
        bookingRepository.findAllById(bookingIds).forEach(b -> refs.put(b.getId(), BookingService.bookingRef(b.getBookingNumber())));
        return InvoicePdfRenderer.write(invoice, lines, taxes, agency, booking, costComponents, voucherRefs,
                bookingIds.stream().map(refs::get).filter(java.util.Objects::nonNull).toList());
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
            predicates.add((root, query, cb) -> {
                jakarta.persistence.criteria.Subquery<String> billed = query.subquery(String.class);
                jakarta.persistence.criteria.Root<InvoiceLineItem> line = billed.from(InvoiceLineItem.class);
                billed.select(line.get("invoiceId")).where(cb.equal(line.get("bookingId"), bookingId));
                return cb.or(cb.equal(root.get("bookingId"), bookingId), root.get("id").in(billed));
            });
        }
        if (from != null) {
            predicates.add((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("invoiceDate"), from));
        }
        if (to != null) {
            predicates.add((root, query, cb) -> cb.lessThanOrEqualTo(root.get("invoiceDate"), to));
        }
        Specification<Invoice> spec = Specification.allOf(predicates);

        if (pageable != null) {
            org.springframework.data.domain.Page<Invoice> page = invoiceRepository.findAll(spec, pageable);
            Map<String, String> refs = bookingRefs(page.getContent());
            return PagedResponse.from(page, i -> toListItem(i, refs));
        }
        List<Invoice> invoices = invoiceRepository.findAll(spec, Sort.by(Sort.Direction.DESC, "createdAt"));
        Map<String, String> refs = bookingRefs(invoices);
        return invoices.stream().map(i -> toListItem(i, refs)).toList();
    }

    /** Invoice id -> its bookings' BK-numbers for one page of invoices, in two queries - people search invoices by them. */
    private Map<String, String> bookingRefs(List<Invoice> invoices) {
        Map<String, String> refs = new java.util.HashMap<>();
        if (invoices.isEmpty()) return refs;
        Map<String, java.util.LinkedHashSet<String>> idsByInvoice = new java.util.HashMap<>();
        invoices.forEach(i -> {
            java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
            if (i.getBookingId() != null) ids.add(i.getBookingId());
            idsByInvoice.put(i.getId(), ids);
        });
        invoiceLineItemRepository.findByInvoiceIdInOrderBySortOrderAsc(invoices.stream().map(Invoice::getId).toList()).stream()
                .filter(l -> l.getBookingId() != null).forEach(l -> idsByInvoice.get(l.getInvoiceId()).add(l.getBookingId()));
        Map<String, String> byBooking = new java.util.HashMap<>();
        bookingRepository.findAllById(idsByInvoice.values().stream().flatMap(java.util.Set::stream).distinct().toList())
                .forEach(b -> byBooking.put(b.getId(), BookingService.bookingRef(b.getBookingNumber())));
        idsByInvoice.forEach((invoiceId, ids) -> refs.put(invoiceId,
                ids.stream().map(byBooking::get).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.joining(", "))));
        return refs;
    }

    @Transactional
    public InvoiceResponse issue(String id) {
        Invoice invoice = findById(id);
        InvoiceLifecyclePolicy.assertIssuable(invoice.getStatus());

        List<InvoiceLineItem> lines = invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc(id);
        if (lines.isEmpty()) {
            throw new IllegalStateException("At least one line is required to issue an invoice");
        }

        if (invoice.getSupplyNature() == null) {
            // Without it the invoice has no GST slab or place of supply - it would go out tax-free by accident.
            throw new IllegalStateException("Choose the nature of supply before generating the invoice");
        }
        assertMatchesVouchers(invoice, lines);
        assertFareBreakdowns(lines);
        assertTravellers(lines);
        LocalDate today = invoice.getInvoiceDate() != null ? invoice.getInvoiceDate() : FinancialYear.today();
        assertIssueDate(invoice, today);
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
        postInvoiceRaisedJournal(invoice);
        // A returning customer's standing deposit draws down automatically the moment a real
        // invoice exists against them - see PaymentReceiptService#applyAvailableWallet.
        paymentReceiptService.applyAvailableWallet(invoice);
        syncBookings(invoice);

        auditService.recordCreate(AuditEntityType.INVOICE, invoice.getId(), invoice.getInvoiceNumber());
        log.info("Invoice issued: id={}, number={}", invoice.getId(), number);
        return toResponse(invoice, lines);
    }

    /**
     * Retired per ACCOUNTING_REDESIGN_SPEC.md §5.2: "proforma" is not a word a non-accounts
     * operator understands, and the legacy software this module reproduces has no such state at
     * all. {@code InvoiceDocumentType.PROFORMA}/{@code InvoiceLifecycle.PROFORMA_ISSUED} stay in
     * their enums so an already-issued historical proforma keeps reading and printing correctly
     * - this method only blocks creating a NEW one. If an advance-request document is wanted
     * again later, the spec's own recommendation is a separate document called a Payment Request
     * or Estimate, never a state of an invoice.
     */
    @Transactional
    public InvoiceResponse issueProforma(String id) {
        throw new IllegalStateException(
                "Proforma has been retired - finalise the invoice directly instead of issuing a proforma first");
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
        LocalDate today = FinancialYear.today();
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
        postInvoiceRaisedJournal(taxInvoice);
        syncBookings(taxInvoice);

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
        // Nothing is owed on a cancelled invoice (the ledger credits it back in full just below).
        invoice.setBalanceDue(BigDecimal.ZERO);
        invoice.setBalanceDueInr(BigDecimal.ZERO);
        invoiceRepository.save(invoice);
        if (hadLedgerDebit) {
            postCancellationReversal(invoice);
            reverseInvoiceRaisedJournal(invoice);
        }
        syncBookings(invoice);

        List<AuditChange> changes = AuditSnapshot.diff(before, AuditSnapshot.of(invoice, AUDITED));
        auditService.recordUpdate(AuditEntityType.INVOICE, invoice.getId(), invoice.getInvoiceNumber(), changes);
        log.info("Invoice cancelled: id={}", id);
        return toResponse(invoice);
    }

    // ---------------------------------------------------------------- internals

    /** Every booking the invoice bills gets its invoiced / received figures worked out again. */
    private void syncBookings(Invoice invoice) {
        invoiceLineSource.bookingIdsOf(invoice).forEach(bookingAccountingSync::syncPayment);
    }

    /** The agency's logo for the printed invoice; none when it has no logo or the file can't be read. */
    private byte[] logoOf(Tenant agency) {
        if (agency.getLogoKey() == null || agency.getLogoKey().isBlank()) return null;
        try (java.io.InputStream in = fileStorageService.retrieve(agency.getLogoKey()).getInputStream()) {
            return in.readAllBytes();
        } catch (Exception e) {
            log.warn("Invoice logo not readable: key={}", agency.getLogoKey());
            return null;
        }
    }

    /** Who a user id is - an agent, or the agency's owner (whose user id is the tenant's). */
    private String userName(String userId) {
        if (userId == null) return null;
        return agentRepository.findById(userId).map(com.voyra.crm.entity.Agent::getName)
                .or(() -> tenantRepository.findById(userId).map(Tenant::getOwnerName)).orElse(null);
    }

    /** An invoice billed from vouchers is issued only while its lines still are what those bookings have left to bill. */
    /** A flight ticket's invoice prints its fare breakdown: the accountant enters one for every e-ticket it bills (an "Other" voucher has no fare). */
    private void assertFareBreakdowns(List<InvoiceLineItem> lines) {
        List<String> ids = lines.stream().map(InvoiceLineItem::getBookingDocumentId).filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return;
        List<com.voyra.crm.entity.BookingDocument> vouchers = bookingDocumentRepository.findAllById(ids);
        Map<String, com.voyra.crm.enums.BookingType> types = bookingRepository.findAllById(vouchers.stream()
                .map(com.voyra.crm.entity.BookingDocument::getBookingId).distinct().toList()).stream()
                .collect(java.util.stream.Collectors.toMap(Booking::getId, Booking::getType));
        vouchers.stream().filter(d -> types.get(d.getBookingId()) == com.voyra.crm.enums.BookingType.FLIGHT
                        && d.getDocType() == com.voyra.crm.enums.BookingDocumentType.E_TICKET && d.getCharges() == null).findFirst()
                .ifPresent(d -> {
                    throw new IllegalStateException("Add the fare breakdown for " + (d.getReferenceNumber() != null
                            ? "PNR " + d.getReferenceNumber() : "the flight voucher") + " before generating the invoice");
                });
    }

    /** The air invoice names every ticket's travellers: a flight booking's e-ticket line can't be issued without them. */
    private void assertTravellers(List<InvoiceLineItem> lines) {
        List<String> ids = lines.stream().filter(l -> l.getTravellers() == null || l.getTravellers().isEmpty())
                .map(InvoiceLineItem::getBookingDocumentId).filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return;
        List<com.voyra.crm.entity.BookingDocument> vouchers = bookingDocumentRepository.findAllById(ids);
        Map<String, com.voyra.crm.enums.BookingType> types = bookingRepository.findAllById(vouchers.stream()
                .map(com.voyra.crm.entity.BookingDocument::getBookingId).distinct().toList()).stream()
                .collect(java.util.stream.Collectors.toMap(Booking::getId, Booking::getType));
        vouchers.stream().filter(d -> types.get(d.getBookingId()) == com.voyra.crm.enums.BookingType.FLIGHT
                        && d.getDocType() == com.voyra.crm.enums.BookingDocumentType.E_TICKET).findFirst()
                .ifPresent(d -> {
                    throw new IllegalStateException("Add the travellers for " + (d.getReferenceNumber() != null
                            ? "PNR " + d.getReferenceNumber() : "the air ticket") + " on the booking's voucher - delete this draft first, then draft it again");
                });
    }

    private void assertMatchesVouchers(Invoice invoice, List<InvoiceLineItem> lines) {
        if (lines.stream().noneMatch(l -> l.getBookingDocumentId() != null)) {
            return;
        }
        for (String id : invoiceLineSource.bookingIdsOf(invoice)) {
            Booking b = bookingRepository.findById(id).orElse(null);
            if (b == null) continue;
            BigDecimal billed = lines.stream().filter(l -> id.equals(l.getBookingId() != null ? l.getBookingId() : invoice.getBookingId()))
                    .map(InvoiceLineItem::getLineSubtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal left = inInvoiceCurrency(invoice, invoiceLineSource.linesFor(b, invoice.getServiceCategory() != null ? invoice.getServiceCategory() : categoryOf(b))
                    .lines()).stream().map(l -> l.getQuantity().multiply(l.getUnitPrice()).setScale(2, RoundingMode.HALF_UP))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (billed.compareTo(left) != 0) {
                throw new IllegalStateException(BookingService.bookingRef(b.getBookingNumber())
                        + "'s vouchers changed since this draft was saved - save the draft again to bring it up to date");
            }
        }
    }

    /** Never dated ahead, nor before an invoice already issued in its series - the numbers stay in date order. */
    private void assertIssueDate(Invoice invoice, LocalDate date) {
        if (date.isAfter(FinancialYear.today())) {
            throw new IllegalArgumentException("The invoice date can't be in the future");
        }
        if (invoice.getServiceCategory() == null) {
            return;
        }
        LocalDate latest = invoiceRepository.latestIssuedDate(invoice.getServiceCategory(), FinancialYear.of(date));
        if (latest != null && date.isBefore(latest)) {
            throw new IllegalArgumentException(invoice.getServiceCategory().documentTitle() + "s are already issued up to "
                    + latest.format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy")) + " - date this one on or after it");
        }
    }

    /** e.g. "Tax invoice ITI/26-27/0012 · BK-00042, BK-00043 · NAHKSJ, HYJJUY" - what the ledger and journal rows say. */
    private String narrationOf(Invoice invoice, String prefix) {
        List<String> ids = invoiceLineSource.bookingIdsOf(invoice);
        // findAllById has no order: bookings by number, vouchers in line order.
        String bookings = bookingRepository.findAllById(ids).stream()
                .sorted(java.util.Comparator.comparing(Booking::getBookingNumber, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .map(b -> BookingService.bookingRef(b.getBookingNumber())).collect(java.util.stream.Collectors.joining(", "));
        List<String> voucherIds = invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc(invoice.getId()).stream()
                .map(InvoiceLineItem::getBookingDocumentId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<String, com.voyra.crm.entity.BookingDocument> vouchers = bookingDocumentRepository.findAllById(voucherIds).stream()
                .collect(java.util.stream.Collectors.toMap(com.voyra.crm.entity.BookingDocument::getId, d -> d));
        String refs = voucherIds.stream().map(vouchers::get).filter(java.util.Objects::nonNull)
                .map(com.voyra.crm.entity.BookingDocument::getReferenceNumber).filter(r -> r != null && !r.isBlank())
                .distinct().collect(java.util.stream.Collectors.joining(", "));
        String text = java.util.stream.Stream.of(prefix, bookings, refs).filter(p -> !p.isBlank())
                .collect(java.util.stream.Collectors.joining(" · "));
        return text.length() > 255 ? text.substring(0, 254) + "…" : text;
    }

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
                narrationOf(invoice, "Tax invoice " + invoice.getInvoiceNumber() + " raised"), invoice.getBookingId(),
                invoice.getCurrencyCode(), invoice.getFxRateToInr(),
                invoice.getGrandTotal(), BigDecimal.ZERO, invoice.getGrandTotalInr(), BigDecimal.ZERO));
    }

    /** Undoes the INVOICE_RAISED debit for a tax invoice cancelled before any receipt exists against it. */
    private void postCancellationReversal(Invoice invoice) {
        customerLedgerService.post(new LedgerPosting(
                invoice.getClientId(), FinancialYear.today(), LedgerEntryType.REVERSAL,
                LedgerSourceType.INVOICE, invoice.getId(), invoice.getInvoiceNumber(),
                "Tax invoice " + invoice.getInvoiceNumber() + " cancelled: " + invoice.getCancelReason(),
                invoice.getBookingId(), invoice.getCurrencyCode(), invoice.getFxRateToInr(),
                BigDecimal.ZERO, invoice.getGrandTotal(), BigDecimal.ZERO, invoice.getGrandTotalInr()));
    }

    /**
     * Posting-rule-table rows 1/2/3 (ACCOUNTING_EXPANSION_ARCHITECTURE.md §1.5) - the GL side of
     * {@link #postInvoiceRaised}, called immediately beside it in the same transaction (Rule
     * 1.8.1). {@code null} {@link Invoice#getBillingModel()} reads as PRINCIPAL throughout, the
     * pre-existing behaviour. A missing booking (should not happen - bookingId is required at
     * draft creation) is treated as "departure past or absent," i.e. immediate recognition,
     * rather than blocking the issue.
     */
    private void postInvoiceRaisedJournal(Invoice invoice) {
        BigDecimal taxableInr = invoice.getTaxableValueInr();
        BigDecimal gstInr = invoice.getGstTotalInr() != null ? invoice.getGstTotalInr() : BigDecimal.ZERO;
        BigDecimal tcsInr = invoice.getTcsAmountInr() != null ? invoice.getTcsAmountInr() : BigDecimal.ZERO;
        BigDecimal grandTotalInr = invoice.getGrandTotalInr();
        String narration = narrationOf(invoice, "Tax invoice " + invoice.getInvoiceNumber() + " raised");

        List<com.voyra.crm.models.JournalLinePosting> lines = new ArrayList<>();
        lines.add(com.voyra.crm.models.JournalLinePosting.debitParty(
                com.voyra.crm.enums.SystemAccount.ACCOUNTS_RECEIVABLE.code(), "CLIENT", invoice.getClientId(), grandTotalInr, narration));

        com.voyra.crm.enums.JournalPurpose purpose;
        if (invoice.getBillingModel() == InvoiceBillingModel.COMMISSION_AGENT) {
            purpose = com.voyra.crm.enums.JournalPurpose.INVOICE_RAISED_COMMISSION_AGENT;
            BigDecimal feeInr = resolveTaxableBase(invoice).multiply(invoice.getFxRateToInr()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal passThroughInr = taxableInr.subtract(feeInr);
            if (passThroughInr.compareTo(BigDecimal.ZERO) < 0) {
                passThroughInr = BigDecimal.ZERO;
            }
            if (passThroughInr.compareTo(BigDecimal.ZERO) > 0) {
                lines.add(com.voyra.crm.models.JournalLinePosting.credit(
                        com.voyra.crm.enums.SystemAccount.CLIENT_PASS_THROUGH_PAYABLE.code(), passThroughInr, "Supplier disbursement pass-through"));
            }
            if (feeInr.compareTo(BigDecimal.ZERO) > 0) {
                lines.add(com.voyra.crm.models.JournalLinePosting.credit(
                        com.voyra.crm.enums.SystemAccount.SERVICE_FEE_INCOME.code(), feeInr, "Agency service fee"));
            }
            if (gstInr.compareTo(BigDecimal.ZERO) > 0) {
                lines.add(com.voyra.crm.models.JournalLinePosting.credit(
                        com.voyra.crm.enums.SystemAccount.OUTPUT_GST_PAYABLE.code(), gstInr, "Output GST on fee"));
            }
        } else {
            // An invoice billing several bookings is recognised on issue: deferral follows one booking's departure.
            Booking booking = invoice.getBookingId() != null && invoiceLineSource.bookingIdsOf(invoice).size() == 1
                    ? bookingRepository.findById(invoice.getBookingId()).orElse(null) : null;
            boolean departureFuture = booking != null && booking.getDepartureDate() != null
                    && booking.getDepartureDate().isAfter(FinancialYear.today());
            purpose = departureFuture
                    ? com.voyra.crm.enums.JournalPurpose.INVOICE_RAISED_DEFERRED
                    : com.voyra.crm.enums.JournalPurpose.INVOICE_RAISED_RECOGNIZED;
            String revenueAccountCode = departureFuture
                    ? com.voyra.crm.enums.SystemAccount.UNEARNED_TOUR_REVENUE.code()
                    : com.voyra.crm.enums.SystemAccount.salesCode(
                            invoice.getServiceCategory() != null ? invoice.getServiceCategory() : InvoiceServiceCategory.MISCELLANEOUS);
            if (taxableInr.compareTo(BigDecimal.ZERO) > 0) {
                lines.add(com.voyra.crm.models.JournalLinePosting.credit(revenueAccountCode, taxableInr,
                        departureFuture ? "Unearned until departure" : "Recognized on issue"));
            }
            if (gstInr.compareTo(BigDecimal.ZERO) > 0) {
                lines.add(com.voyra.crm.models.JournalLinePosting.credit(
                        com.voyra.crm.enums.SystemAccount.OUTPUT_GST_PAYABLE.code(), gstInr, "Output GST"));
            }
            if (tcsInr.compareTo(BigDecimal.ZERO) > 0) {
                lines.add(com.voyra.crm.models.JournalLinePosting.credit(
                        com.voyra.crm.enums.SystemAccount.TCS_PAYABLE.code(), tcsInr, "TCS collected"));
            }
        }

        if (lines.size() < 2) {
            // Degenerate zero-value invoice - nothing to balance against the AR debit. Extremely
            // unlikely (grandTotal would also be zero) but a one-line entry would fail to post.
            log.warn("Invoice {} raised with no non-zero credit lines to post - skipping journal", invoice.getId());
            return;
        }

        journalService.post(new com.voyra.crm.models.JournalPosting(
                invoice.getInvoiceDate(), com.voyra.crm.enums.JournalSourceType.INVOICE, invoice.getId(), purpose,
                narration, invoice.getBookingId(), invoice.getBranchId(), withConversionPaisa(lines)));
    }

    /**
     * Each figure of a foreign-currency invoice is converted at its rate on its own, so the credits can miss the
     * converted grand total (the debit, first line) by a paisa or two; the first credit, the base amount, takes it.
     * A larger gap is a real error and is left for the journal's own balance check to refuse.
     */
    static List<com.voyra.crm.models.JournalLinePosting> withConversionPaisa(List<com.voyra.crm.models.JournalLinePosting> lines) {
        BigDecimal gap = lines.get(0).debitAmount().subtract(lines.stream().skip(1)
                .map(com.voyra.crm.models.JournalLinePosting::creditAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        if (gap.signum() == 0 || gap.abs().compareTo(new BigDecimal("0.05")) > 0) {
            return lines;
        }
        List<com.voyra.crm.models.JournalLinePosting> out = new ArrayList<>(lines);
        com.voyra.crm.models.JournalLinePosting first = out.get(1);
        out.set(1, new com.voyra.crm.models.JournalLinePosting(first.accountCode(), first.partyType(), first.partyId(),
                first.currencyCode(), first.fxRateToInr(), first.debitAmount(), first.creditAmount().add(gap), first.narration()));
        return out;
    }

    /** Reverses whichever of the three INVOICE_RAISED_* purposes was actually posted for this invoice - at most one exists. */
    private void reverseInvoiceRaisedJournal(Invoice invoice) {
        for (com.voyra.crm.enums.JournalPurpose purpose : List.of(
                com.voyra.crm.enums.JournalPurpose.INVOICE_RAISED_DEFERRED,
                com.voyra.crm.enums.JournalPurpose.INVOICE_RAISED_RECOGNIZED,
                com.voyra.crm.enums.JournalPurpose.INVOICE_RAISED_COMMISSION_AGENT)) {
            journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(
                    com.voyra.crm.enums.JournalSourceType.INVOICE, invoice.getId(), purpose)
                    .ifPresent(entry -> journalService.reverse(entry.getId(), "Invoice " + invoice.getInvoiceNumber() + " cancelled: " + invoice.getCancelReason()));
        }
    }

    private void applyDraftFields(Invoice invoice, InvoiceDraftRequest request) {
        String currencyCode = request.getCurrencyCode() != null && !request.getCurrencyCode().isBlank()
                ? request.getCurrencyCode().toUpperCase() : baseCurrency.code();
        invoice.setCurrencyCode(currencyCode);

        if (baseCurrency.code().equals(currencyCode)) {
            invoice.setFxRateToInr(BigDecimal.ONE);
            invoice.setFxRateSource(FxRateSource.INR_IDENTITY);
        } else {
            if (request.getFxRateToInr() == null || request.getFxRateToInr().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Enter the rate: what 1 " + currencyCode + " is in " + baseCurrency.code());
            }
            invoice.setFxRateToInr(request.getFxRateToInr());
            invoice.setFxRateSource(FxRateSource.MANUAL);
        }

        invoice.setSupplyNature(request.getSupplyNature());
        invoice.setPlaceOfSupplyOverride(request.getPlaceOfSupplyCodeOverride());
        invoice.setExportOfServiceRequested(Boolean.TRUE.equals(request.getExportOfServiceRequested()));
        invoice.setDueDate(request.getDueDate());
        if (request.getInvoiceDate() != null && request.getInvoiceDate().isAfter(FinancialYear.today())) {
            throw new IllegalArgumentException("The invoice date can't be in the future");
        }
        invoice.setInvoiceDate(request.getInvoiceDate());
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
                    .bookingId(req.getBookingId())
                    .bookingDocumentId(req.getBookingDocumentId())
                    .charges(req.getCharges())
                    .travellers(req.getTravellers())
                    .legs(req.getLegs())
                    .purchaseCurrency(req.getPurchaseCurrency())
                    .purchaseRate(req.getPurchaseRate())
                    .createdAt(LocalDateTime.now())
                    .build();
            lines.add(line);

            subtotal = subtotal.add(lineSubtotal);
            discountTotal = discountTotal.add(discount);
        }

        BigDecimal taxableValue = subtotal.subtract(discountTotal);
        invoice.setSubtotal(subtotal);
        invoice.setDiscountTotal(discountTotal);
        invoice.setTaxableValue(taxableValue);
        return lines;
    }

    /**
     * {@code invoice.taxableValue} stays the full package amount regardless of billing model - it
     * is what the pass-through block and the printed total are built from. This is only the base
     * actually fed into the per-row GST computation below, and only {@link InvoiceBillingModel#COMMISSION_AGENT}
     * ever differs from it (Decision 8, ACCOUNTING_EXPANSION_ARCHITECTURE.md §8).
     *
     * <p>The fee is {@code booking.sellingPrice - sum(cost component net costs)} when the booking
     * has components (Rule 8.3 - this is also what the printed pass-through block lists). With no
     * components, it falls back to the booking's own {@code markupMode}/{@code markupValue} against
     * {@code booking.netCost}, mirroring {@code BookingService#computeMarkupAmount}. With neither,
     * there is nothing to isolate as a fee, so this silently behaves as PRINCIPAL for this invoice.
     */
    private BigDecimal resolveTaxableBase(Invoice invoice) {
        BigDecimal fullPackage = invoice.getTaxableValue();
        if (invoice.getBillingModel() != InvoiceBillingModel.COMMISSION_AGENT || invoice.getBookingId() == null) {
            return fullPackage;
        }
        if (invoice.getPassThroughAmount() != null) {
            return fullPackage.subtract(invoice.getPassThroughAmount()).max(BigDecimal.ZERO).min(fullPackage);
        }
        Booking booking = bookingRepository.findById(invoice.getBookingId()).orElse(null);
        if (booking == null) {
            return fullPackage;
        }
        List<BookingCostComponent> components = bookingCostComponentRepository.findByBookingIdOrderBySortOrder(booking.getId());
        if (!components.isEmpty()) {
            BigDecimal componentTotal = components.stream()
                    .map(BookingCostComponent::getNetCostInr).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal sellingPrice = booking.getSellingPrice() != null ? booking.getSellingPrice() : BigDecimal.ZERO;
            BigDecimal fee = sellingPrice.subtract(componentTotal);
            return fee.compareTo(BigDecimal.ZERO) > 0 ? fee : BigDecimal.ZERO;
        }
        // Voucher-priced booking: the fee is what this invoice bills above the cost it covers. Cost
        // already covered by the booking's earlier commission-agent invoices is their taxable value
        // less the fee they posted, so an extra invoice for a later voucher carries only that
        // voucher's markup, and several vouchers with different markups add up to the real profit.
        BigDecimal netCost = booking.getNetCost() != null ? booking.getNetCost() : BigDecimal.ZERO;
        if (netCost.signum() > 0) {
            BigDecimal costHere = netCost.subtract(costCoveredByEarlierInvoices(invoice)).max(BigDecimal.ZERO);
            return fullPackage.subtract(costHere).max(BigDecimal.ZERO).min(fullPackage);
        }
        log.info("Commission-agent invoice {} has no cost components or markup to isolate a fee from - "
                + "taxing the full package instead", invoice.getId());
        return fullPackage;
    }

    /** Σ (taxable − posted fee) over the booking's other issued commission-agent invoices: the supplier cost they already billed. */
    private BigDecimal costCoveredByEarlierInvoices(Invoice invoice) {
        BigDecimal covered = BigDecimal.ZERO;
        String fee = com.voyra.crm.enums.SystemAccount.SERVICE_FEE_INCOME.code();
        for (Invoice other : invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc(
                invoice.getBookingId(), InvoiceDocumentType.TAX_INVOICE, InvoiceLifecycle.CANCELLED)) {
            if (other.getId().equals(invoice.getId()) || other.getStatus() == InvoiceLifecycle.DRAFT
                    || other.getBillingModel() != InvoiceBillingModel.COMMISSION_AGENT) {
                continue;
            }
            var posted = journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(
                    com.voyra.crm.enums.JournalSourceType.INVOICE, other.getId(),
                    com.voyra.crm.enums.JournalPurpose.INVOICE_RAISED_COMMISSION_AGENT);
            if (posted.isEmpty()) {
                continue;
            }
            BigDecimal postedFee = journalLineRepository.findByJournalEntryIdOrderByLineNo(posted.get().getId()).stream()
                    .filter(l -> fee.equals(l.getAccountCode()))
                    .map(l -> l.getCreditAmountInr() != null ? l.getCreditAmountInr() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal taxable = other.getTaxableValueInr() != null ? other.getTaxableValueInr() : BigDecimal.ZERO;
            covered = covered.add(taxable.subtract(postedFee).max(BigDecimal.ZERO));
        }
        return covered;
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
        BigDecimal taxableBase = resolveTaxableBase(invoice);
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

                // TCS on an overseas package is on the whole package the client pays for, never just the agency's markup.
                BigDecimal base = kind == TaxKind.TCS ? invoice.getTaxableValue() : taxableBase;
                BigDecimal amount = req.getMode() == TaxLineMode.FLAT
                        ? (req.getFlatAmount() != null ? req.getFlatAmount() : BigDecimal.ZERO)
                        : pct(base, req.getRatePercent() != null ? req.getRatePercent() : BigDecimal.ZERO);

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
        // The client pays the full package; only the GST base differs by billing model (a commission-agent
        // invoice taxes just the fee, the supplier's cost passes through untaxed but is still billed).
        BigDecimal packageValue = invoice.getTaxableValue();
        BigDecimal grandTotal = packageValue.add(gstTotal).add(tcsAmount).add(otherTaxTotal);
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
        invoice.setTaxableValueInr(scaleToInr(packageValue, fx));
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
        List<String> bookingIds = invoiceLineSource.bookingIdsOf(i);
        Map<String, Booking> bookings = new java.util.LinkedHashMap<>();
        bookingRepository.findAllById(bookingIds).forEach(b -> bookings.put(b.getId(), b));
        Map<String, com.voyra.crm.entity.BookingDocument> vouchers = new java.util.HashMap<>();
        bookingDocumentRepository.findAllById(lines.stream().map(InvoiceLineItem::getBookingDocumentId)
                .filter(java.util.Objects::nonNull).distinct().toList()).forEach(d -> vouchers.put(d.getId(), d));
        // The working behind the price is for the owner and the accountant only.
        boolean costs = !SecurityContextUtil.getCurrentUserOrThrow().isAgent();
        Booking first = i.getBookingId() != null ? bookings.get(i.getBookingId()) : null;
        return InvoiceResponse.builder()
                .bookings(bookingIds.stream().filter(bookings::containsKey).map(id -> InvoiceBookingRefResponse.builder()
                        .id(id).bookingRef(BookingService.bookingRef(bookings.get(id).getBookingNumber()))
                        .supplier(bookings.get(id).getSupplier()).build()).toList())
                .agentName(first != null ? first.getAgentName() : null)
                .passThroughAmount(costs ? i.getPassThroughAmount() : null)
                .id(i.getId()).invoiceNumber(i.getInvoiceNumber()).financialYear(i.getFinancialYear())
                .documentType(i.getDocumentType()).status(i.getStatus()).serviceCategory(i.getServiceCategory())
                .billingModel(i.getBillingModel())
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
                .lines(lines.stream().map(l -> {
                    InvoiceLineItemResponse r = toLineResponse(l);
                    String bookingId = l.getBookingId() != null ? l.getBookingId() : i.getBookingId();
                    Booking b = bookingId != null ? bookings.get(bookingId) : null;
                    com.voyra.crm.entity.BookingDocument v = l.getBookingDocumentId() != null ? vouchers.get(l.getBookingDocumentId()) : null;
                    r.setBookingId(bookingId);
                    r.setBookingRef(b != null ? BookingService.bookingRef(b.getBookingNumber()) : null);
                    r.setBookingDocumentId(l.getBookingDocumentId());
                    r.setVoucherReference(v != null ? v.getReferenceNumber() : null);
                    r.setPurchaseCost(costs && v != null ? v.getNetCost() : null);
                    return r;
                }).toList())
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
                .charges(l.getCharges())
                .travellers(l.getTravellers()).legs(l.getLegs())
                .build();
    }

    private static InvoiceListItemResponse toListItem(Invoice i, Map<String, String> bookingRefs) {
        return InvoiceListItemResponse.builder()
                .id(i.getId()).invoiceNumber(i.getInvoiceNumber()).financialYear(i.getFinancialYear())
                .documentType(i.getDocumentType()).status(i.getStatus()).serviceCategory(i.getServiceCategory())
                .clientId(i.getClientId()).clientName(i.getClientName())
                .bookingId(i.getBookingId()).bookingRef(bookingRefs.get(i.getId())).agentId(i.getAgentId())
                .currencyCode(i.getCurrencyCode()).grandTotal(i.getGrandTotal()).grandTotalInr(i.getGrandTotalInr())
                .balanceDue(i.getBalanceDue()).balanceDueInr(i.getBalanceDueInr())
                .amountReceivedInr(scaleToInr(i.getAmountReceived(), i.getFxRateToInr()))
                .creditNoteTotalInr(scaleToInr(i.getCreditNoteTotal(), i.getFxRateToInr()))
                .invoiceDate(i.getInvoiceDate()).dueDate(i.getDueDate()).issuedAt(i.getIssuedAt())
                .build();
    }
}
