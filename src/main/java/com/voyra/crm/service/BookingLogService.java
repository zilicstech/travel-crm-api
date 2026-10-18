package com.voyra.crm.service;

import com.voyra.crm.dto.BookingCreateRequest;
import com.voyra.crm.dto.BookingLogRequest;
import com.voyra.crm.dto.BookingResponse;
import com.voyra.crm.entity.LeadMember;
import com.voyra.crm.entity.LeadService;
import com.voyra.crm.entity.Vendor;
import com.voyra.crm.repository.BookingDocumentRepository;
import com.voyra.crm.repository.LeadMemberRepository;
import com.voyra.crm.repository.LeadServiceRepository;
import com.voyra.crm.repository.VendorRepository;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.BookingLogRequestMapper;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Logs a supplier-confirmed booking together with its voucher. Not {@code @Transactional}: the
 * file is stored first (object storage is barred inside a transaction, blueprint §8.6), then
 * {@link BookingService#logBooking} writes the booking and its document row in one transaction.
 * If that fails the stored file is deleted, so a rejected booking leaves nothing behind.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingLogService {

    private static final String DOCUMENT_FILE_CATEGORY = "booking-documents";

    private final BookingService bookingService;
    private final BookingDocumentRepository bookingDocumentRepository;
    private final LeadServiceRepository leadServiceRepository;
    private final LeadMemberRepository leadMemberRepository;
    private final VendorRepository vendorRepository;
    private final FileStorageService fileStorageService;

    public BookingResponse log(BookingLogRequest request, MultipartFile voucher) {
        if (voucher == null || voucher.isEmpty()) {
            throw new IllegalArgumentException("Voucher file is required");
        }
        LeadService service = null;
        List<LeadMember> members = List.of();
        if (request.getServiceId() != null && !request.getServiceId().isBlank()) {
            service = leadServiceRepository.findById(request.getServiceId())
                    .orElseThrow(() -> new IllegalArgumentException("Service not found: " + request.getServiceId()));
            members = leadMemberRepository.findByLeadIdOrderByCreatedAtAsc(service.getLeadId());
        }
        String vendorName = request.getVendorId() == null || request.getVendorId().isBlank() ? null
                : vendorRepository.findById(request.getVendorId()).map(Vendor::getName)
                        .orElseThrow(() -> new IllegalArgumentException("Vendor not found: " + request.getVendorId()));
        BookingCreateRequest create = BookingLogRequestMapper.toCreateRequest(request, service, members, vendorName);

        String documentId = UniqueIdResolver.resolve(bookingDocumentRepository::existsById);
        String tenantId = SecurityContextUtil.getCurrentUserOrThrow().tenantId();
        String fileKey = fileStorageService.store(tenantId, DOCUMENT_FILE_CATEGORY, documentId, voucher);
        try {
            BookingResponse booking = bookingService.logBooking(create, documentId, fileKey,
                    voucher.getOriginalFilename(), voucher.getContentType(),
                    BookingLogRequestMapper.voucherTypeFor(create.getType()), request.getReference().trim());
            log.info("Booking logged with voucher: bookingId={}, serviceId={}", booking.getId(), request.getServiceId());
            return booking;
        } catch (RuntimeException e) {
            fileStorageService.delete(fileKey);
            throw e;
        }
    }
}
