package com.voyra.crm.service;

import com.voyra.crm.dto.AuditChange;
import com.voyra.crm.dto.SupplierInvoiceDraftRequest;
import com.voyra.crm.dto.SupplierInvoiceLineItemRequest;
import com.voyra.crm.dto.SupplierInvoiceLineItemResponse;
import com.voyra.crm.dto.SupplierInvoiceListItemResponse;
import com.voyra.crm.dto.SupplierInvoiceResponse;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.BookingCostComponent;
import com.voyra.crm.entity.SupplierInvoice;
import com.voyra.crm.entity.SupplierInvoiceLineItem;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.entity.Vendor;
import com.voyra.crm.enums.AuditEntityType;
import com.voyra.crm.enums.SupplierInvoiceStatus;
import com.voyra.crm.enums.SupplierLedgerEntryType;
import com.voyra.crm.enums.SupplierLedgerSourceType;
import com.voyra.crm.models.SupplierLedgerPosting;
import com.voyra.crm.repository.BookingCostComponentRepository;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.SupplierInvoiceLineItemRepository;
import com.voyra.crm.repository.SupplierInvoiceRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.repository.VendorRepository;
import com.voyra.crm.repository.spec.SupplierInvoiceSpecifications;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.SupplierInvoiceLifecyclePolicy;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Draft CRUD, approve, cancel, and file attachment for supplier bills - the accounts-payable
 * mirror of {@code InvoiceDocumentService}. The load-bearing difference: GST here is RECORDED,
 * never computed. A bill's line-level CGST/SGST/IGST are exactly what the supplier printed;
 * {@link #assertRecordedTaxIsConsistent} only validates that the header sums agree with the lines
 * and that the CGST+SGST-vs-IGST split matches {@code vendor.stateCode} against the agency's own -
 * it never calls {@code TaxEngine}. See ARCHITECTURE-SPINE AD-2.
 *
 * <p>Never writes to {@code booking.netCost}/{@code profit} - AD-9. {@code bookingId} here is
 * context and a report join key only.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SupplierInvoiceService {

    private static final String FILE_CATEGORY = "supplier-invoices";

    private final SupplierInvoiceRepository supplierInvoiceRepository;
    private final SupplierInvoiceLineItemRepository lineItemRepository;
    private final VendorRepository vendorRepository;
    private final BookingRepository bookingRepository;
    private final BookingCostComponentRepository bookingCostComponentRepository;
    private final TenantRepository tenantRepository;
    private final SupplierLedgerService supplierLedgerService;
    private final AuditService auditService;
    private final FileStorageService fileStorageService;
    private final JournalService journalService;
    private final com.voyra.crm.repository.JournalEntryRepository journalEntryRepository;

    @Transactional
    public SupplierInvoiceResponse createDraft(SupplierInvoiceDraftRequest request) {
        Vendor vendor = findVendor(request.getVendorId());
        Booking booking = null;
        if (request.getBookingId() != null && !request.getBookingId().isBlank()) {
            booking = bookingRepository.findById(request.getBookingId())
                    .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + request.getBookingId()));
        }

        SupplierInvoice invoice = SupplierInvoice.builder()
                .id(UniqueIdResolver.resolve(supplierInvoiceRepository::existsById))
                .vendorId(vendor.getId())
                .vendorName(vendor.getName())
                .status(SupplierInvoiceStatus.DRAFT)
                .createdDate(LocalDateTime.now())
                .createdBy(currentUserId())
                .build();
        if (booking != null) {
            invoice.setBookingId(booking.getId());
            invoice.setLeadId(booking.getLeadId());
            invoice.setServiceType(booking.getServiceType());
        }

        applyDraftFields(invoice, request, vendor);
        List<SupplierInvoiceLineItem> lines = applyLines(invoice, request.getLines());
        supplierInvoiceRepository.save(invoice);
        lineItemRepository.saveAll(lines);

        auditService.recordCreate(AuditEntityType.SUPPLIER_INVOICE, invoice.getId(), labelFor(invoice));
        log.info("Supplier bill draft created: id={}, vendorId={}", invoice.getId(), vendor.getId());
        return toResponse(invoice, lines);
    }

    /**
     * Called from {@code BookingService#createBooking} the moment a booking is placed with a
     * real Vendor and a positive net cost. When the booking carries no
     * {@link BookingCostComponent} rows (the common case, and every booking created before
     * Decision 7), this drafts exactly one bill at the booking's blended net cost - unchanged
     * from the original behaviour. When the booking DOES carry cost components, it groups them
     * by {@code vendorId} and drafts one bill per vendor, each with one line per component
     * (ACCOUNTING_EXPANSION_ARCHITECTURE.md Decision 7, Rule 7.2); components naming no vendor
     * raise no bill. Every draft posts nothing to the ledger - only {@link #approve} does.
     */
    @Transactional
    public List<SupplierInvoiceResponse> createAutoDraft(Booking booking, String vendorId) {
        List<BookingCostComponent> components = bookingCostComponentRepository.findByBookingIdOrderBySortOrder(booking.getId());
        if (components.isEmpty()) {
            return List.of(createAutoDraftSingle(booking, vendorId, booking.getNetCost()));
        }

        Map<String, List<BookingCostComponent>> byVendor = components.stream()
                .filter(c -> c.getVendorId() != null && !c.getVendorId().isBlank())
                .collect(Collectors.groupingBy(BookingCostComponent::getVendorId, LinkedHashMap::new, Collectors.toList()));

        List<SupplierInvoiceResponse> created = new ArrayList<>();
        for (Map.Entry<String, List<BookingCostComponent>> entry : byVendor.entrySet()) {
            created.add(createAutoDraftFanned(booking, entry.getKey(), entry.getValue()));
        }
        return created;
    }

    /** The pre-Decision-7 path: one vendor, one line, at the booking's blended net cost. GST is
     *  left at zero - the agent's net cost is an estimate, not what the supplier will actually
     *  bill tax on. */
    private SupplierInvoiceResponse createAutoDraftSingle(Booking booking, String vendorId, BigDecimal netCost) {
        Vendor vendor = findVendor(vendorId);
        SupplierInvoice invoice = newAutoDraftHeader(booking, vendor,
                "Drafted automatically from booking " + booking.getId()
                        + " at the agent's net cost - confirm against the supplier's real invoice before approving.");

        SupplierInvoiceLineItemRequest line = new SupplierInvoiceLineItemRequest();
        line.setDescription((booking.getDestination() != null ? booking.getDestination() : "Booking") + " - " + booking.getId());
        line.setServiceType(booking.getServiceType());
        line.setQuantity(BigDecimal.ONE);
        line.setUnitPrice(netCost);
        line.setGstRatePercent(BigDecimal.ZERO);
        line.setCgstAmount(BigDecimal.ZERO);
        line.setSgstAmount(BigDecimal.ZERO);
        line.setIgstAmount(BigDecimal.ZERO);

        return saveAutoDraft(invoice, List.of(line));
    }

    /** One vendor's share of a fanned-out booking: one bill, one line per cost component naming
     *  that vendor. Component amounts are taken at face value in the bill's own currency
     *  (INR identity) - true multi-currency per component is Decision 3's forex engine, not
     *  this one. */
    private SupplierInvoiceResponse createAutoDraftFanned(Booking booking, String vendorId, List<BookingCostComponent> vendorComponents) {
        Vendor vendor = findVendor(vendorId);
        SupplierInvoice invoice = newAutoDraftHeader(booking, vendor,
                "Drafted automatically from booking " + booking.getId() + "'s cost components for this vendor - "
                        + "confirm against the supplier's real invoice before approving.");

        List<SupplierInvoiceLineItemRequest> lines = new ArrayList<>();
        for (BookingCostComponent component : vendorComponents) {
            SupplierInvoiceLineItemRequest line = new SupplierInvoiceLineItemRequest();
            line.setDescription(component.getDescription() != null && !component.getDescription().isBlank()
                    ? component.getDescription()
                    : component.getServiceType() + " - " + booking.getId());
            line.setServiceType(component.getServiceType());
            line.setQuantity(BigDecimal.ONE);
            line.setUnitPrice(component.getNetCost());
            line.setGstRatePercent(BigDecimal.ZERO);
            line.setCgstAmount(BigDecimal.ZERO);
            line.setSgstAmount(BigDecimal.ZERO);
            line.setIgstAmount(BigDecimal.ZERO);
            lines.add(line);
        }
        return saveAutoDraft(invoice, lines);
    }

    private SupplierInvoice newAutoDraftHeader(Booking booking, Vendor vendor, String notes) {
        return SupplierInvoice.builder()
                .id(UniqueIdResolver.resolve(supplierInvoiceRepository::existsById))
                .vendorId(vendor.getId())
                .vendorName(vendor.getName())
                .status(SupplierInvoiceStatus.DRAFT)
                .kind(com.voyra.crm.enums.SupplierInvoiceKind.PURCHASE)
                .category(booking.getType())
                .supplierGstin(vendor.getGstNumber())
                .supplierStateCode(vendor.getStateCode())
                .invoiceDate(LocalDate.now())
                .receivedOn(LocalDate.now())
                .bookingId(booking.getId())
                .leadId(booking.getLeadId())
                .serviceType(booking.getServiceType())
                .referenceNote(booking.getPnr())
                .currencyCode("INR")
                .fxRateToInr(BigDecimal.ONE)
                .fxRateSource("INR_IDENTITY")
                .itcEligibility(com.voyra.crm.enums.ItcEligibility.ELIGIBLE)
                .isReverseCharge(false)
                .tdsRatePercent(BigDecimal.ZERO)
                .autoDrafted(true)
                .notes(notes)
                .createdDate(LocalDateTime.now())
                .createdBy(currentUserId())
                .build();
    }

    private SupplierInvoiceResponse saveAutoDraft(SupplierInvoice invoice, List<SupplierInvoiceLineItemRequest> lineRequests) {
        List<SupplierInvoiceLineItem> lines = applyLines(invoice, lineRequests);
        supplierInvoiceRepository.save(invoice);
        lineItemRepository.saveAll(lines);

        auditService.recordCreate(AuditEntityType.SUPPLIER_INVOICE, invoice.getId(), labelFor(invoice));
        log.info("Supplier bill auto-drafted from booking: id={}, bookingId={}, vendorId={}",
                invoice.getId(), invoice.getBookingId(), invoice.getVendorId());
        return toResponse(invoice, lines);
    }

    @Transactional
    public SupplierInvoiceResponse updateDraft(String id, SupplierInvoiceDraftRequest request) {
        SupplierInvoice invoice = findById(id);
        SupplierInvoiceLifecyclePolicy.assertEditable(invoice.getStatus());
        Vendor vendor = findVendor(request.getVendorId());

        invoice.setVendorId(vendor.getId());
        invoice.setVendorName(vendor.getName());
        applyDraftFields(invoice, request, vendor);
        invoice.setUpdatedAt(LocalDateTime.now());
        invoice.setUpdatedBy(currentUserId());

        List<SupplierInvoiceLineItem> lines = applyLines(invoice, request.getLines());
        supplierInvoiceRepository.save(invoice);
        lineItemRepository.deleteBySupplierInvoiceId(id);
        lineItemRepository.saveAll(lines);

        log.info("Supplier bill draft updated: id={}", id);
        return toResponse(invoice, lines);
    }

    @Transactional
    public void deleteDraft(String id) {
        SupplierInvoice invoice = findById(id);
        SupplierInvoiceLifecyclePolicy.assertDeletable(invoice.getStatus());
        lineItemRepository.deleteBySupplierInvoiceId(id);
        supplierInvoiceRepository.delete(invoice);
        log.info("Supplier bill draft deleted: id={}", id);
    }

    @Transactional(readOnly = true)
    public SupplierInvoiceResponse get(String id) {
        return toResponse(findById(id));
    }

    /** Sorted by due-date urgency (Rule 7.6), not recency - a bill with no due date sorts last so
     *  an undated draft never buries an overdue one. */
    private static final Sort PAYABLES_URGENCY_SORT =
            Sort.by(new Sort.Order(Sort.Direction.ASC, "dueDate").nullsLast());

    @Transactional(readOnly = true)
    public List<SupplierInvoiceListItemResponse> list(String vendorId, SupplierInvoiceStatus status, String bookingId) {
        Specification<SupplierInvoice> spec = Specification
                .allOf(List.of(
                        SupplierInvoiceSpecifications.vendorIs(vendorId),
                        SupplierInvoiceSpecifications.statusIs(status),
                        SupplierInvoiceSpecifications.bookingIs(bookingId)));
        return supplierInvoiceRepository.findAll(spec, PAYABLES_URGENCY_SORT).stream()
                .map(SupplierInvoiceService::toListItem)
                .toList();
    }

    @Transactional
    public SupplierInvoiceResponse approve(String id, String overrideReason) {
        SupplierInvoice invoice = findById(id);
        SupplierInvoiceLifecyclePolicy.assertApprovable(invoice.getStatus());
        List<SupplierInvoiceLineItem> lines = lineItemRepository.findBySupplierInvoiceIdOrderBySortOrder(id);
        if (lines.isEmpty()) {
            throw new IllegalStateException("At least one line is required to approve a bill");
        }
        Vendor vendor = invoice.getVendorId() != null ? findVendor(invoice.getVendorId()) : null;
        assertRecordedTaxIsConsistent(invoice, lines);

        List<AuditChange> overrideChange = applyCostCapGate(invoice, overrideReason);
        if (invoice.getStatus() == SupplierInvoiceStatus.PENDING_APPROVAL) {
            // Sent to an owner for override - nothing is booked to the ledger until they approve it.
            supplierInvoiceRepository.save(invoice);
            log.info("Supplier bill sent for owner approval (over cost cap): id={}, grandTotal={}", id, invoice.getGrandTotal());
            return toResponse(invoice, lines);
        }

        invoice.setStatus(SupplierInvoiceStatus.APPROVED);
        invoice.setApprovedAt(LocalDateTime.now());
        invoice.setApprovedBy(currentUserId());
        if (invoice.getDueDate() == null) {
            Integer termsDays = vendor != null ? vendor.getPaymentTermsDays() : null;
            LocalDate base = invoice.getInvoiceDate() != null ? invoice.getInvoiceDate() : LocalDate.now();
            invoice.setDueDate(termsDays != null ? base.plusDays(termsDays) : base);
        }
        supplierInvoiceRepository.save(invoice);

        supplierLedgerService.post(new SupplierLedgerPosting(
                invoice.getVendorId(), invoice.getInvoiceDate() != null ? invoice.getInvoiceDate() : LocalDate.now(),
                SupplierLedgerEntryType.BILL_BOOKED, SupplierLedgerSourceType.SUPPLIER_INVOICE, invoice.getId(),
                invoice.getSupplierInvoiceNumber(), "Supplier bill " + labelFor(invoice) + " booked",
                invoice.getBookingId(), invoice.getCurrencyCode(), invoice.getFxRateToInr(),
                BigDecimal.ZERO, invoice.getGrandTotal(), BigDecimal.ZERO, invoice.getGrandTotalInr()));
        postBillBookedJournal(invoice);

        auditService.recordUpdate(AuditEntityType.SUPPLIER_INVOICE, invoice.getId(), labelFor(invoice), overrideChange);
        log.info("Supplier bill approved: id={}, vendorId={}, grandTotal={}", id, invoice.getVendorId(), invoice.getGrandTotal());
        return toResponse(invoice, lines);
    }

    /**
     * Rule 7.5 - a bill whose total exceeds the sum of its booking's cost components for that
     * same vendor needs an {@code AGENCY_OWNER}'s override to approve. Returns the audit-change
     * list to record (empty when no cap applied or the cap was not exceeded). Mutates
     * {@code invoice.status} to {@code PENDING_APPROVAL} in place when a non-owner hits the cap
     * and no override has been supplied yet - the caller checks that status afterwards and stops
     * before booking anything to the ledger.
     */
    private List<AuditChange> applyCostCapGate(SupplierInvoice invoice, String overrideReason) {
        BigDecimal cap = costComponentCap(invoice);
        if (cap == null || invoice.getGrandTotal().compareTo(cap) <= 0) {
            return List.of();
        }

        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        boolean hasOverride = overrideReason != null && !overrideReason.isBlank();

        if (!principal.isAgencyOwner()) {
            if (invoice.getStatus() == SupplierInvoiceStatus.PENDING_APPROVAL) {
                throw new AccessDeniedException(
                        "This bill exceeds the booking's quoted cost and needs an Agency Owner's override to approve");
            }
            invoice.setStatus(SupplierInvoiceStatus.PENDING_APPROVAL);
            return List.of();
        }
        if (!hasOverride) {
            throw new IllegalArgumentException("This bill exceeds the booking's quoted cost cap of " + cap
                    + " by " + invoice.getGrandTotal().subtract(cap) + " - an override reason is required to approve it anyway");
        }
        return List.of(AuditChange.builder()
                .field("costCapOverride")
                .oldValue("cap " + cap)
                .newValue(overrideReason)
                .build());
    }

    /** Null when there is nothing to compare against - no booking, no vendor, or the booking
     *  names no cost components for this vendor (pre-Decision-7 bookings, always). */
    private BigDecimal costComponentCap(SupplierInvoice invoice) {
        if (invoice.getBookingId() == null || invoice.getVendorId() == null) {
            return null;
        }
        List<BookingCostComponent> components =
                bookingCostComponentRepository.findByBookingIdAndVendorId(invoice.getBookingId(), invoice.getVendorId());
        if (components.isEmpty()) {
            return null;
        }
        return components.stream().map(BookingCostComponent::getNetCost).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Transactional
    public SupplierInvoiceResponse cancel(String id, String reason) {
        SupplierInvoice invoice = findById(id);
        SupplierInvoiceLifecyclePolicy.assertCancellable(invoice.getStatus());
        if (invoice.getAmountPaid().compareTo(BigDecimal.ZERO) > 0) {
            throw new IllegalStateException("This bill has payments recorded against it - reverse them first");
        }
        boolean wasBooked = invoice.getStatus() == SupplierInvoiceStatus.APPROVED;
        invoice.setStatus(SupplierInvoiceStatus.CANCELLED);
        invoice.setCancelledAt(LocalDateTime.now());
        invoice.setCancelledBy(currentUserId());
        invoice.setCancelReason(reason);
        supplierInvoiceRepository.save(invoice);

        if (wasBooked) {
            supplierLedgerService.post(new SupplierLedgerPosting(
                    invoice.getVendorId(), LocalDate.now(),
                    SupplierLedgerEntryType.REVERSAL, SupplierLedgerSourceType.SUPPLIER_INVOICE, invoice.getId(),
                    invoice.getSupplierInvoiceNumber(), "Supplier bill " + labelFor(invoice) + " cancelled",
                    invoice.getBookingId(), invoice.getCurrencyCode(), invoice.getFxRateToInr(),
                    invoice.getGrandTotal(), BigDecimal.ZERO, invoice.getGrandTotalInr(), BigDecimal.ZERO));
            journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(
                    com.voyra.crm.enums.JournalSourceType.SUPPLIER_INVOICE, invoice.getId(),
                    com.voyra.crm.enums.JournalPurpose.SUPPLIER_BILL_BOOKED)
                    .ifPresent(entry -> journalService.reverse(entry.getId(), "Supplier bill cancelled: " + reason));
        }
        log.info("Supplier bill cancelled: id={}, reason={}", id, reason);
        return toResponse(invoice);
    }

    /** Not {@code @Transactional} - object storage work must not sit inside a transaction (§8.6). */
    public SupplierInvoiceResponse attachFile(String id, MultipartFile file) {
        SupplierInvoice invoice = findById(id);
        String tenantId = SecurityContextUtil.getCurrentUserOrThrow().tenantId();
        String previousFileKey = invoice.getFileKey();
        String fileKey = fileStorageService.store(tenantId, FILE_CATEGORY, id, file);

        invoice.setFileKey(fileKey);
        invoice.setFileName(file.getOriginalFilename());
        invoice.setContentType(file.getContentType());
        supplierInvoiceRepository.save(invoice);

        if (previousFileKey != null) {
            fileStorageService.delete(previousFileKey);
        }
        log.info("Supplier bill file attached: id={}", id);
        return toResponse(invoice);
    }

    /** Not {@code @Transactional} - see {@link #attachFile}. */
    public SupplierInvoiceFileContent downloadFile(String id) {
        SupplierInvoice invoice = findById(id);
        if (invoice.getFileKey() == null) {
            throw new IllegalArgumentException("No file attached to this bill");
        }
        Resource resource = fileStorageService.retrieve(invoice.getFileKey());
        return new SupplierInvoiceFileContent(resource, invoice.getFileName(), invoice.getContentType());
    }

    public record SupplierInvoiceFileContent(Resource resource, String filename, String contentType) {
    }

    /**
     * See class javadoc - line sums against the header, and the CGST+SGST vs IGST split against
     * intra vs inter state, where "state" means the SUPPLIER's state (the vendor's own, or the
     * GSTIN's state code when supplied) against the AGENCY's own registered state - never the
     * vendor against itself, which would be vacuously true. A blocked/reverse-charge bill and an
     * inter-state bill correctly show zero CGST/SGST; the check only rejects a bill claiming
     * CGST+SGST while inter-state, or IGST while intra-state.
     */
    private void assertRecordedTaxIsConsistent(SupplierInvoice invoice, List<SupplierInvoiceLineItem> lines) {
        BigDecimal lineCgst = lines.stream().map(SupplierInvoiceLineItem::getCgstAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal lineSgst = lines.stream().map(SupplierInvoiceLineItem::getSgstAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal lineIgst = lines.stream().map(SupplierInvoiceLineItem::getIgstAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal lineTaxable = lines.stream().map(SupplierInvoiceLineItem::getTaxableValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal lineTotal = lines.stream().map(SupplierInvoiceLineItem::getLineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);

        if (invoice.getTaxableValue().compareTo(lineTaxable) != 0) {
            throw new IllegalStateException("Header taxable value does not match the sum of the lines");
        }
        if (invoice.getGrandTotal().subtract(invoice.getRoundOff()).compareTo(lineTotal) != 0) {
            throw new IllegalStateException("Header grand total does not match the sum of the lines");
        }

        boolean claimsIntraState = lineCgst.compareTo(BigDecimal.ZERO) > 0 || lineSgst.compareTo(BigDecimal.ZERO) > 0;
        boolean claimsInterState = lineIgst.compareTo(BigDecimal.ZERO) > 0;
        if (claimsIntraState && claimsInterState) {
            throw new IllegalStateException("A bill cannot carry both CGST/SGST and IGST across its lines");
        }
        String agencyStateCode = currentAgency().getStateCode();
        if (agencyStateCode != null && invoice.getSupplierStateCode() != null) {
            boolean sameState = agencyStateCode.equals(invoice.getSupplierStateCode());
            if (claimsIntraState && !sameState) {
                throw new IllegalStateException("This bill records CGST/SGST but the supplier's state differs from the agency's own - expected IGST");
            }
            if (claimsInterState && sameState) {
                throw new IllegalStateException("This bill records IGST but the supplier's state matches the agency's own - expected CGST+SGST");
            }
        }
    }

    private Tenant currentAgency() {
        String tenantId = SecurityContextUtil.getCurrentUserOrThrow().tenantId();
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalStateException("Agency not found: " + tenantId));
    }

    private void applyDraftFields(SupplierInvoice invoice, SupplierInvoiceDraftRequest request, Vendor vendor) {
        invoice.setKind(request.getKind() != null ? request.getKind() : invoice.getKind());
        invoice.setCategory(request.getCategory());
        invoice.setSupplierInvoiceNumber(request.getSupplierInvoiceNumber());
        invoice.setSupplierGstin(request.getSupplierGstin() != null ? request.getSupplierGstin()
                : (vendor.getGstNumber()));
        invoice.setSupplierStateCode(vendor.getStateCode());
        invoice.setInvoiceDate(request.getInvoiceDate());
        invoice.setReceivedOn(request.getReceivedOn() != null ? request.getReceivedOn() : LocalDate.now());
        invoice.setReferenceNote(request.getReferenceNote());
        invoice.setCurrencyCode(request.getCurrencyCode() != null ? request.getCurrencyCode() : "INR");
        if (!"INR".equals(invoice.getCurrencyCode())) {
            if (request.getFxRateToInr() == null || request.getFxRateToInr().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("fxRateToInr is required and must be positive for a non-INR bill");
            }
            invoice.setFxRateToInr(request.getFxRateToInr());
            invoice.setFxRateSource("MANUAL");
        } else {
            invoice.setFxRateToInr(BigDecimal.ONE);
            invoice.setFxRateSource("INR_IDENTITY");
        }
        invoice.setItcEligibility(request.getItcEligibility() != null ? request.getItcEligibility() : invoice.getItcEligibility());
        invoice.setItcNote(request.getItcNote());
        invoice.setIsReverseCharge(request.getIsReverseCharge() != null ? request.getIsReverseCharge() : false);
        invoice.setTdsSection(request.getTdsSection());
        invoice.setTdsRatePercent(request.getTdsRatePercent() != null ? request.getTdsRatePercent() : BigDecimal.ZERO);
        invoice.setNotes(request.getNotes());
    }

    private List<SupplierInvoiceLineItem> applyLines(SupplierInvoice invoice, List<SupplierInvoiceLineItemRequest> requests) {
        List<SupplierInvoiceLineItem> lines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO, discountTotal = BigDecimal.ZERO, taxable = BigDecimal.ZERO;
        BigDecimal cgst = BigDecimal.ZERO, sgst = BigDecimal.ZERO, igst = BigDecimal.ZERO;

        int order = 0;
        if (requests != null) {
            for (SupplierInvoiceLineItemRequest req : requests) {
                BigDecimal lineSubtotal = req.getQuantity().multiply(req.getUnitPrice()).setScale(2, RoundingMode.HALF_UP);
                BigDecimal discount = req.getDiscountAmount() != null ? req.getDiscountAmount() : BigDecimal.ZERO;
                BigDecimal lineTaxable = lineSubtotal.subtract(discount);
                BigDecimal lineCgst = req.getCgstAmount();
                BigDecimal lineSgst = req.getSgstAmount();
                BigDecimal lineIgst = req.getIgstAmount();
                BigDecimal lineTotal = lineTaxable.add(lineCgst).add(lineSgst).add(lineIgst);

                lines.add(SupplierInvoiceLineItem.builder()
                        .id(UniqueIdResolver.resolve(lineItemRepository::existsById))
                        .supplierInvoiceId(invoice.getId())
                        .sortOrder(order++)
                        .description(req.getDescription())
                        .sacCode(req.getSacCode())
                        .serviceType(req.getServiceType())
                        .quantity(req.getQuantity())
                        .unitPrice(req.getUnitPrice())
                        .lineSubtotal(lineSubtotal)
                        .discountAmount(discount)
                        .taxableValue(lineTaxable)
                        .gstRatePercent(req.getGstRatePercent())
                        .cgstAmount(lineCgst)
                        .sgstAmount(lineSgst)
                        .igstAmount(lineIgst)
                        .lineTotal(lineTotal)
                        .createdAt(LocalDateTime.now())
                        .build());

                subtotal = subtotal.add(lineSubtotal);
                discountTotal = discountTotal.add(discount);
                taxable = taxable.add(lineTaxable);
                cgst = cgst.add(lineCgst);
                sgst = sgst.add(lineSgst);
                igst = igst.add(lineIgst);
            }
        }

        BigDecimal gstTotal = cgst.add(sgst).add(igst);
        BigDecimal tdsAmount = taxable.multiply(invoice.getTdsRatePercent() != null ? invoice.getTdsRatePercent() : BigDecimal.ZERO)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal grandTotal = taxable.add(gstTotal).add(invoice.getCessAmount() != null ? invoice.getCessAmount() : BigDecimal.ZERO)
                .add(invoice.getRoundOff() != null ? invoice.getRoundOff() : BigDecimal.ZERO);

        invoice.setSubtotal(subtotal);
        invoice.setDiscountTotal(discountTotal);
        invoice.setTaxableValue(taxable);
        invoice.setCgstAmount(cgst);
        invoice.setSgstAmount(sgst);
        invoice.setIgstAmount(igst);
        invoice.setGstTotal(gstTotal);
        invoice.setTdsAmount(tdsAmount);
        invoice.setGrandTotal(grandTotal);
        invoice.setTaxableValueInr(taxable.multiply(invoice.getFxRateToInr()).setScale(2, RoundingMode.HALF_UP));
        invoice.setGstTotalInr(gstTotal.multiply(invoice.getFxRateToInr()).setScale(2, RoundingMode.HALF_UP));
        invoice.setGrandTotalInr(grandTotal.multiply(invoice.getFxRateToInr()).setScale(2, RoundingMode.HALF_UP));
        invoice.setBalanceDue(grandTotal.subtract(invoice.getAmountPaid()).subtract(invoice.getCreditNoteTotal()));
        invoice.setBalanceDueInr(invoice.getGrandTotalInr()
                .subtract(invoice.getAmountPaid().multiply(invoice.getFxRateToInr()).setScale(2, RoundingMode.HALF_UP))
                .subtract(invoice.getCreditNoteTotal().multiply(invoice.getFxRateToInr()).setScale(2, RoundingMode.HALF_UP)));

        return lines;
    }

    /**
     * Posting-rule-table row 8. Ineligible/blocked input GST is folded into the purchase debit
     * instead of {@code 1400} - GST that cannot be claimed back is a cost, not an asset.
     * {@code invoice.category} is a {@code BookingType}, not an {@code InvoiceServiceCategory},
     * so the purchase account is resolved the same way the customer-invoice side resolves its
     * sales account: via the linked booking's own type and {@code internationalTrip} flag when
     * one exists, falling back to MISCELLANEOUS when the bill has no booking.
     */
    private void postBillBookedJournal(SupplierInvoice invoice) {
        BigDecimal taxableInr = invoice.getTaxableValueInr();
        BigDecimal gstInr = invoice.getGstTotalInr() != null ? invoice.getGstTotalInr() : BigDecimal.ZERO;
        BigDecimal grandTotalInr = invoice.getGrandTotalInr();
        boolean itcEligible = invoice.getItcEligibility() == com.voyra.crm.enums.ItcEligibility.ELIGIBLE;
        BigDecimal purchaseInr = itcEligible ? taxableInr : taxableInr.add(gstInr);
        BigDecimal inputGstInr = itcEligible ? gstInr : BigDecimal.ZERO;

        com.voyra.crm.enums.InvoiceServiceCategory category = resolvePurchaseCategory(invoice);
        String narration = "Supplier bill " + labelFor(invoice) + " booked";

        List<com.voyra.crm.models.JournalLinePosting> lines = new ArrayList<>();
        if (purchaseInr.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(com.voyra.crm.models.JournalLinePosting.debit(
                    com.voyra.crm.enums.SystemAccount.purchaseCode(category), purchaseInr, narration));
        }
        if (inputGstInr.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(com.voyra.crm.models.JournalLinePosting.debit(
                    com.voyra.crm.enums.SystemAccount.INPUT_GST_RECEIVABLE.code(), inputGstInr, "Input GST"));
        }
        if (lines.isEmpty()) {
            return;
        }
        lines.add(com.voyra.crm.models.JournalLinePosting.creditParty(
                com.voyra.crm.enums.SystemAccount.ACCOUNTS_PAYABLE.code(), "VENDOR", invoice.getVendorId(), grandTotalInr, narration));

        journalService.post(new com.voyra.crm.models.JournalPosting(
                invoice.getInvoiceDate() != null ? invoice.getInvoiceDate() : LocalDate.now(),
                com.voyra.crm.enums.JournalSourceType.SUPPLIER_INVOICE, invoice.getId(),
                com.voyra.crm.enums.JournalPurpose.SUPPLIER_BILL_BOOKED, narration,
                invoice.getBookingId(), null, lines));
    }

    private com.voyra.crm.enums.InvoiceServiceCategory resolvePurchaseCategory(SupplierInvoice invoice) {
        if (invoice.getBookingId() == null) {
            return com.voyra.crm.enums.InvoiceServiceCategory.MISCELLANEOUS;
        }
        return bookingRepository.findById(invoice.getBookingId())
                .map(b -> com.voyra.crm.enums.InvoiceServiceCategory.forBooking(b.getType(), Boolean.TRUE.equals(b.getInternationalTrip())))
                .orElse(com.voyra.crm.enums.InvoiceServiceCategory.MISCELLANEOUS);
    }

    private Vendor findVendor(String vendorId) {
        return vendorRepository.findById(vendorId)
                .orElseThrow(() -> new IllegalArgumentException("Vendor not found: " + vendorId));
    }

    private SupplierInvoice findById(String id) {
        SupplierInvoice invoice = supplierInvoiceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Supplier bill not found: " + id));
        assertOwnerOrAccountant();
        return invoice;
    }

    /** Payables data is Owner/Accountant only - never agent-scoped, matching CreditNoteController's gate. */
    private void assertOwnerOrAccountant() {
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        if (principal.isAgent()) {
            throw new AccessDeniedException("Payables are not accessible to an Agent");
        }
    }

    private String currentUserId() {
        return SecurityContextUtil.getCurrentUserOrThrow().userId();
    }

    private String labelFor(SupplierInvoice i) {
        return i.getVendorName() + (i.getSupplierInvoiceNumber() != null ? " / " + i.getSupplierInvoiceNumber() : "");
    }

    static SupplierInvoiceListItemResponse toListItem(SupplierInvoice i) {
        boolean overdue = i.getDueDate() != null && i.getDueDate().isBefore(LocalDate.now())
                && i.getBalanceDue().compareTo(BigDecimal.ZERO) > 0;
        return SupplierInvoiceListItemResponse.builder()
                .id(i.getId()).vendorId(i.getVendorId()).vendorName(i.getVendorName())
                .kind(i.getKind()).category(i.getCategory()).status(i.getStatus())
                .supplierInvoiceNumber(i.getSupplierInvoiceNumber()).bookingId(i.getBookingId())
                .currencyCode(i.getCurrencyCode()).grandTotal(i.getGrandTotal()).grandTotalInr(i.getGrandTotalInr())
                .balanceDue(i.getBalanceDue()).balanceDueInr(i.getBalanceDueInr())
                .invoiceDate(i.getInvoiceDate()).dueDate(i.getDueDate()).overdue(overdue)
                .autoDrafted(i.getAutoDrafted())
                .build();
    }

    private SupplierInvoiceResponse toResponse(SupplierInvoice invoice) {
        return toResponse(invoice, lineItemRepository.findBySupplierInvoiceIdOrderBySortOrder(invoice.getId()));
    }

    private SupplierInvoiceResponse toResponse(SupplierInvoice i, List<SupplierInvoiceLineItem> lines) {
        return SupplierInvoiceResponse.builder()
                .id(i.getId()).vendorId(i.getVendorId()).vendorName(i.getVendorName())
                .kind(i.getKind()).category(i.getCategory()).status(i.getStatus())
                .supplierInvoiceNumber(i.getSupplierInvoiceNumber()).supplierGstin(i.getSupplierGstin())
                .supplierStateCode(i.getSupplierStateCode())
                .invoiceDate(i.getInvoiceDate()).receivedOn(i.getReceivedOn()).dueDate(i.getDueDate())
                .bookingId(i.getBookingId()).leadId(i.getLeadId()).serviceType(i.getServiceType())
                .referenceNote(i.getReferenceNote())
                .currencyCode(i.getCurrencyCode()).fxRateToInr(i.getFxRateToInr()).fxRateSource(i.getFxRateSource())
                .subtotal(i.getSubtotal()).discountTotal(i.getDiscountTotal()).taxableValue(i.getTaxableValue())
                .cgstAmount(i.getCgstAmount()).sgstAmount(i.getSgstAmount()).igstAmount(i.getIgstAmount())
                .gstTotal(i.getGstTotal()).cessAmount(i.getCessAmount()).roundOff(i.getRoundOff())
                .grandTotal(i.getGrandTotal())
                .itcEligibility(i.getItcEligibility()).itcNote(i.getItcNote()).isReverseCharge(i.getIsReverseCharge())
                .tdsSection(i.getTdsSection()).tdsRatePercent(i.getTdsRatePercent()).tdsAmount(i.getTdsAmount())
                .taxableValueInr(i.getTaxableValueInr()).gstTotalInr(i.getGstTotalInr()).grandTotalInr(i.getGrandTotalInr())
                .amountPaid(i.getAmountPaid()).creditNoteTotal(i.getCreditNoteTotal())
                .balanceDue(i.getBalanceDue()).balanceDueInr(i.getBalanceDueInr())
                .approvedAt(i.getApprovedAt()).approvedBy(i.getApprovedBy())
                .cancelledAt(i.getCancelledAt()).cancelledBy(i.getCancelledBy()).cancelReason(i.getCancelReason())
                .notes(i.getNotes()).autoDrafted(i.getAutoDrafted()).hasFile(i.getFileKey() != null).createdDate(i.getCreatedDate())
                .lines(lines.stream().map(SupplierInvoiceService::toLineResponse).toList())
                .build();
    }

    private static SupplierInvoiceLineItemResponse toLineResponse(SupplierInvoiceLineItem l) {
        return SupplierInvoiceLineItemResponse.builder()
                .id(l.getId()).sortOrder(l.getSortOrder()).description(l.getDescription())
                .sacCode(l.getSacCode()).serviceType(l.getServiceType())
                .quantity(l.getQuantity()).unitPrice(l.getUnitPrice())
                .lineSubtotal(l.getLineSubtotal()).discountAmount(l.getDiscountAmount())
                .taxableValue(l.getTaxableValue()).gstRatePercent(l.getGstRatePercent())
                .cgstAmount(l.getCgstAmount()).sgstAmount(l.getSgstAmount()).igstAmount(l.getIgstAmount())
                .lineTotal(l.getLineTotal())
                .build();
    }
}
