package com.voyra.crm.service;

import com.voyra.crm.dto.SupplierInvoiceDraftRequest;
import com.voyra.crm.dto.SupplierInvoiceLineItemRequest;
import com.voyra.crm.dto.SupplierInvoiceLineItemResponse;
import com.voyra.crm.dto.SupplierInvoiceListItemResponse;
import com.voyra.crm.dto.SupplierInvoiceResponse;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.SupplierInvoice;
import com.voyra.crm.entity.SupplierInvoiceLineItem;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.entity.Vendor;
import com.voyra.crm.enums.AuditEntityType;
import com.voyra.crm.enums.SupplierInvoiceStatus;
import com.voyra.crm.enums.SupplierLedgerEntryType;
import com.voyra.crm.enums.SupplierLedgerSourceType;
import com.voyra.crm.models.SupplierLedgerPosting;
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
import java.util.List;

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
    private final TenantRepository tenantRepository;
    private final SupplierLedgerService supplierLedgerService;
    private final AuditService auditService;
    private final FileStorageService fileStorageService;

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

    @Transactional(readOnly = true)
    public List<SupplierInvoiceListItemResponse> list(String vendorId, SupplierInvoiceStatus status, String bookingId) {
        Specification<SupplierInvoice> spec = Specification
                .allOf(List.of(
                        SupplierInvoiceSpecifications.vendorIs(vendorId),
                        SupplierInvoiceSpecifications.statusIs(status),
                        SupplierInvoiceSpecifications.bookingIs(bookingId)));
        return supplierInvoiceRepository.findAll(spec, Sort.by(Sort.Direction.DESC, "createdDate")).stream()
                .map(SupplierInvoiceService::toListItem)
                .toList();
    }

    @Transactional
    public SupplierInvoiceResponse approve(String id) {
        SupplierInvoice invoice = findById(id);
        SupplierInvoiceLifecyclePolicy.assertApprovable(invoice.getStatus());
        List<SupplierInvoiceLineItem> lines = lineItemRepository.findBySupplierInvoiceIdOrderBySortOrder(id);
        if (lines.isEmpty()) {
            throw new IllegalStateException("At least one line is required to approve a bill");
        }
        Vendor vendor = invoice.getVendorId() != null ? findVendor(invoice.getVendorId()) : null;
        assertRecordedTaxIsConsistent(invoice, lines);

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

        auditService.recordUpdate(AuditEntityType.SUPPLIER_INVOICE, invoice.getId(), labelFor(invoice), List.of());
        log.info("Supplier bill approved: id={}, vendorId={}, grandTotal={}", id, invoice.getVendorId(), invoice.getGrandTotal());
        return toResponse(invoice, lines);
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
                .notes(i.getNotes()).hasFile(i.getFileKey() != null).createdDate(i.getCreatedDate())
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
