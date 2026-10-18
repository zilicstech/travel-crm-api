package com.voyra.crm.service;

import com.voyra.crm.dto.AuditChange;
import com.voyra.crm.dto.BookingDocumentCreateRequest;
import com.voyra.crm.dto.BookingDocumentResponse;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.BookingDocument;
import com.voyra.crm.enums.AuditEntityType;
import com.voyra.crm.enums.LeadTimelineEventType;
import com.voyra.crm.enums.PaymentStatus;
import com.voyra.crm.repository.BookingDocumentRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Voucher documents attached to a booking - an e-ticket, a hotel voucher, an insurance
 * certificate - several per booking, each with its own reference and file. Mirrors
 * the retired LeadVoucherService's discipline exactly: {@code addDocument} is transactional;
 * {@code attachFile}/{@code downloadFile}/{@code deleteDocument} are not, because storing or
 * retrieving a file is object storage work blueprint §8.6 forbids inside a transaction.
 * {@code fileKey} is opaque and never leaves this service.
 *
 * <p>{@link #downloadFile} is the voucher-release point (FRD US-ACC-2.2: a travel voucher must
 * never go out for an unpaid trip): it is gated on {@code booking.paymentStatus} and only an
 * Agency Owner can override it, with a mandatory reason that is written to the audit trail - see
 * {@link #assertReleasable}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingDocumentService {

    private static final String DOCUMENT_FILE_CATEGORY = "booking-documents";

    private final BookingDocumentRepository bookingDocumentRepository;
    private final BookingService bookingService;
    private final LeadTimelineService leadTimelineService;
    private final FileStorageService fileStorageService;
    private final AuditService auditService;

    @Transactional
    public BookingDocumentResponse addDocument(String bookingId, BookingDocumentCreateRequest request) {
        Booking booking = bookingService.lockAccessibleBooking(bookingId);
        if (booking.getBookingStatus() == com.voyra.crm.enums.BookingStatus.CANCELLED
                && (nz(request.getNetCost()).signum() > 0 || request.getMarkupMode() != null)) {
            throw new IllegalStateException("This booking is cancelled - a voucher can't add cost or price to it");
        }

        BookingDocument document = BookingDocument.builder()
                .id(UniqueIdResolver.resolve(bookingDocumentRepository::existsById))
                .bookingId(bookingId)
                .docType(request.getDocType())
                .referenceNumber(request.getReferenceNumber())
                .issuedDate(request.getIssuedDate())
                .notes(request.getNotes())
                .netCost(nz(request.getNetCost()))
                .details(com.voyra.crm.util.VoucherDetails.clean(booking.getType(), request.getDetails()))
                .markupMode(request.getMarkupMode())
                .markupValue(request.getMarkupValue())
                .createdAt(LocalDateTime.now())
                .createdBy(currentUserId())
                .build();
        bookingService.applyVoucher(booking, document, request.getVendorId(), Boolean.TRUE.equals(request.getOverrideCreditLimit()));
        bookingDocumentRepository.save(document);
        bookingService.syncReference(booking, null);

        if (booking.getLeadId() != null) {
            leadTimelineService.record(booking.getLeadId(), booking.getServiceId(),
                    LeadTimelineEventType.BOOKING_DOCUMENT_ADDED,
                    request.getDocType() + " added to " + booking.getDestination() + " booking");
        }
        log.info("Booking document added: bookingId={}, documentId={}", bookingId, document.getId());
        return toResponse(document);
    }

    /** Text fields change in place; a changed cost, markup or supplier takes the old money back and books the new. */
    @Transactional
    public BookingDocumentResponse updateDocument(String bookingId, String documentId, BookingDocumentCreateRequest request) {
        Booking booking = bookingService.lockAccessibleBooking(bookingId);
        BookingDocument document = bookingDocumentRepository.findByIdAndBookingId(documentId, bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));
        bookingService.assertNotInvoiced(document);
        String oldReference = document.getReferenceNumber();
        document.setDocType(request.getDocType());
        document.setDetails(com.voyra.crm.util.VoucherDetails.clean(booking.getType(), request.getDetails()));
        document.setReferenceNumber(request.getReferenceNumber());
        document.setIssuedDate(request.getIssuedDate());
        document.setNotes(request.getNotes());
        String vendorId = request.getVendorId() != null && !request.getVendorId().isBlank() ? request.getVendorId() : booking.getVendorId();
        boolean moneyChanged = nz(request.getNetCost()).compareTo(nz(document.getNetCost())) != 0
                || request.getMarkupMode() != document.getMarkupMode()
                || nz(request.getMarkupValue()).compareTo(nz(document.getMarkupValue())) != 0
                || !java.util.Objects.equals(vendorId, document.getVendorId());
        if (moneyChanged) {
            if (booking.getBookingStatus() == com.voyra.crm.enums.BookingStatus.CANCELLED) {
                throw new IllegalStateException("This booking is cancelled - a voucher's cost or price can't change");
            }
            bookingService.reverseVoucher(booking, document, false);
            document.setSupplierInvoiceId(null);
            document.setNetCost(nz(request.getNetCost()));
            document.setMarkupMode(request.getMarkupMode());
            document.setMarkupValue(request.getMarkupValue());
            bookingService.applyVoucher(booking, document, request.getVendorId(), Boolean.TRUE.equals(request.getOverrideCreditLimit()));
            if (booking.getSellingPrice().compareTo(bookingService.invoicedPrice(bookingId)) < 0) {
                throw new IllegalStateException("This voucher's price is already invoiced - it can't go below that");
            }
        }
        bookingDocumentRepository.save(document);
        bookingService.syncReference(booking, oldReference);
        log.info("Booking document edited: bookingId={}, documentId={}, moneyChanged={}", bookingId, documentId, moneyChanged);
        return toResponse(document);
    }

    @Transactional(readOnly = true)
    public List<BookingDocumentResponse> listForBooking(String bookingId) {
        bookingService.findAccessibleBooking(bookingId);
        java.time.LocalDateTime invoicedAt = bookingService.lastInvoicedAt(bookingId);
        List<BookingDocument> docs = bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc(bookingId);
        java.util.Set<String> approvedBills = bookingService.approvedBillIds(docs);
        return docs.stream().map(d -> BookingService.toDocumentResponse(d, invoicedAt, approvedBills)).toList();
    }

    /** Not {@code @Transactional} - see class javadoc. */
    public void deleteDocument(String bookingId, String documentId) {
        // Soft delete: the row is marked and the stored file is kept.
        bookingService.removeVoucher(bookingId, documentId);
        log.info("Booking document removed: bookingId={}, documentId={}", bookingId, documentId);
    }

    /** Not {@code @Transactional} - see class javadoc. */
    public BookingDocumentResponse attachFile(String bookingId, String documentId, MultipartFile file) {
        bookingService.findAccessibleBooking(bookingId);
        BookingDocument document = bookingDocumentRepository.findByIdAndBookingId(documentId, bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));

        String tenantId = SecurityContextUtil.getCurrentUserOrThrow().tenantId();
        String fileKey = fileStorageService.store(tenantId, DOCUMENT_FILE_CATEGORY, documentId, file);

        document.setFileKey(fileKey);
        document.setFileName(file.getOriginalFilename());
        document.setContentType(file.getContentType());
        bookingDocumentRepository.save(document);
        log.info("Booking document file attached: bookingId={}, documentId={}", bookingId, documentId);
        return toResponse(document);
    }

    /** Not {@code @Transactional} - see class javadoc. */
    public BookingDocumentFileContent downloadFile(String bookingId, String documentId, String overrideReason) {
        Booking booking = bookingService.findAccessibleBooking(bookingId);
        assertReleasable(booking, overrideReason);
        BookingDocument document = bookingDocumentRepository.findByIdAndBookingId(documentId, bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));
        if (document.getFileKey() == null) {
            throw new IllegalArgumentException("No file attached to this document");
        }
        Resource resource = fileStorageService.retrieve(document.getFileKey());
        return new BookingDocumentFileContent(resource, document.getFileName(), document.getContentType());
    }

    /** Not a DTO - never serialized to JSON, only unpacked into a binary response by the controller. */
    public record BookingDocumentFileContent(Resource resource, String filename, String contentType) {
    }

    /**
     * Blocks release of a document for a booking that is not fully paid, unless the caller is an
     * Agency Owner supplying a non-blank reason - that override is then written to the audit
     * trail ({@link AuditService#recordUpdate} opens its own transaction on this call, since this
     * method itself runs outside one - see class javadoc). An Accountant or Agent gets no
     * override path at all, matching the FRD's "unless an authorized manager override is
     * provided."
     */
    private void assertReleasable(Booking booking, String overrideReason) {
        if (booking.getPaymentStatus() == PaymentStatus.PAID) {
            return;
        }
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        boolean hasReason = overrideReason != null && !overrideReason.isBlank();
        if (!principal.isAgencyOwner() || !hasReason) {
            throw new AccessDeniedException("This booking is not fully paid (" + booking.getPaymentStatus()
                    + ") - voucher release is blocked. An Agency Owner can override with a reason.");
        }
        auditService.recordUpdate(AuditEntityType.BOOKING, booking.getId(),
                booking.getClientName() != null ? booking.getClientName() : booking.getId(),
                List.of(AuditChange.builder()
                        .field("voucherReleaseOverride")
                        .oldValue(booking.getPaymentStatus() != null ? booking.getPaymentStatus().toString() : null)
                        .newValue(overrideReason)
                        .build()));
        log.info("Voucher release override: bookingId={}, paymentStatus={}, by={}",
                booking.getId(), booking.getPaymentStatus(), principal.userId());
    }

    private String currentUserId() {
        return SecurityContextUtil.getCurrentUserOrThrow().userId();
    }

    private BookingDocumentResponse toResponse(BookingDocument d) {
        return BookingService.toDocumentResponse(d, bookingService.lastInvoicedAt(d.getBookingId()),
                bookingService.approvedBillIds(List.of(d)));
    }

    private static java.math.BigDecimal nz(java.math.BigDecimal v) {
        return v != null ? v : java.math.BigDecimal.ZERO;
    }
}
