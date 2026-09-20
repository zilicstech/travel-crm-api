package com.voyra.crm.service;

import com.voyra.crm.dto.BookingDocumentCreateRequest;
import com.voyra.crm.dto.BookingDocumentResponse;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.BookingDocument;
import com.voyra.crm.enums.LeadTimelineEventType;
import com.voyra.crm.repository.BookingDocumentRepository;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
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

    @Transactional
    public BookingDocumentResponse addDocument(String bookingId, BookingDocumentCreateRequest request) {
        Booking booking = bookingService.findAccessibleBooking(bookingId);

        BookingDocument document = BookingDocument.builder()
                .id(UniqueIdResolver.resolve(bookingDocumentRepository::existsById))
                .bookingId(bookingId)
                .docType(request.getDocType())
                .referenceNumber(request.getReferenceNumber())
                .issuedDate(request.getIssuedDate())
                .notes(request.getNotes())
                .createdAt(LocalDateTime.now())
                .createdBy(currentUserId())
                .build();
        bookingDocumentRepository.save(document);

        if (booking.getLeadId() != null) {
            leadTimelineService.record(booking.getLeadId(), booking.getServiceId(),
                    LeadTimelineEventType.BOOKING_DOCUMENT_ADDED,
                    request.getDocType() + " added to " + booking.getDestination() + " booking");
        }
        log.info("Booking document added: bookingId={}, documentId={}", bookingId, document.getId());
        return toResponse(document);
    }

    @Transactional(readOnly = true)
    public List<BookingDocumentResponse> listForBooking(String bookingId) {
        bookingService.findAccessibleBooking(bookingId);
        return bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc(bookingId).stream()
                .map(this::toResponse).toList();
    }

    /** Not {@code @Transactional} - see class javadoc. */
    public void deleteDocument(String bookingId, String documentId) {
        bookingService.findAccessibleBooking(bookingId);
        BookingDocument document = bookingDocumentRepository.findByIdAndBookingId(documentId, bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));
        bookingDocumentRepository.delete(document);
        if (document.getFileKey() != null) {
            fileStorageService.delete(document.getFileKey());
        }
        log.info("Booking document removed: bookingId={}, documentId={}", bookingId, documentId);
    }

    /** Not {@code @Transactional} - see class javadoc. */
    public BookingDocumentResponse attachFile(String bookingId, String documentId, MultipartFile file) {
        bookingService.findAccessibleBooking(bookingId);
        BookingDocument document = bookingDocumentRepository.findByIdAndBookingId(documentId, bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));

        String tenantId = SecurityContextUtil.getCurrentUserOrThrow().tenantId();
        String previousFileKey = document.getFileKey();
        String fileKey = fileStorageService.store(tenantId, DOCUMENT_FILE_CATEGORY, documentId, file);

        document.setFileKey(fileKey);
        document.setFileName(file.getOriginalFilename());
        document.setContentType(file.getContentType());
        bookingDocumentRepository.save(document);

        if (previousFileKey != null) {
            fileStorageService.delete(previousFileKey);
        }
        log.info("Booking document file attached: bookingId={}, documentId={}", bookingId, documentId);
        return toResponse(document);
    }

    /** Not {@code @Transactional} - see class javadoc. */
    public BookingDocumentFileContent downloadFile(String bookingId, String documentId) {
        bookingService.findAccessibleBooking(bookingId);
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

    private String currentUserId() {
        return SecurityContextUtil.getCurrentUserOrThrow().userId();
    }

    private BookingDocumentResponse toResponse(BookingDocument d) {
        return BookingDocumentResponse.builder()
                .id(d.getId()).bookingId(d.getBookingId()).docType(d.getDocType())
                .referenceNumber(d.getReferenceNumber()).issuedDate(d.getIssuedDate())
                .hasFile(d.getFileKey() != null).fileName(d.getFileName())
                .notes(d.getNotes()).createdAt(d.getCreatedAt())
                .build();
    }
}
