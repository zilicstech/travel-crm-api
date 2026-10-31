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
    private final BaseCurrency baseCurrency;

    /**
     * A logged air ticket names the lead's travellers it was booked for and the service's flights - what the
     * invoice prints. Not enforced here: a gap is caught when the invoice is generated, and the voucher's next
     * edit asks for them.
     */
    private void nameTheTrip(String documentId, BookingCreateRequest create, List<LeadMember> members) {
        if (create.getType() != com.voyra.crm.enums.BookingType.FLIGHT) return;
        java.util.Map<String, LeadMember> byId = new java.util.HashMap<>();
        members.forEach(m -> byId.put(m.getId(), m));
        List<com.voyra.crm.dto.BookingPassengerRequest> pax = create.getPassengers() != null ? create.getPassengers() : List.of();
        List<com.voyra.crm.dto.VoucherTraveller> travellers = pax.stream()
                .filter(p -> byId.get(p.getLeadMemberId()).getMemberId() != null)
                .map(p -> com.voyra.crm.dto.VoucherTraveller.builder().memberId(byId.get(p.getLeadMemberId()).getMemberId())
                        .name(p.getPassengerName()).paxType(p.getPaxType()).build())
                .toList();
        List<com.voyra.crm.dto.BookingSectorRequest> sectors = pax.isEmpty() || pax.get(0).getSectors() == null ? List.of() : pax.get(0).getSectors();
        List<com.voyra.crm.dto.VoucherLeg> legs = sectors.stream()
                .filter(x -> x.getSectorFrom() != null && x.getSectorTo() != null && x.getTravelDate() != null)
                .map(x -> com.voyra.crm.dto.VoucherLeg.builder().from(x.getSectorFrom()).to(x.getSectorTo())
                        .airline(create.getAirline()).flightNumber(x.getFlightNumber()).cabin(x.getCabinClass())
                        .travelDate(x.getTravelDate()).build())
                .toList();
        if (legs.isEmpty() && create.getFlightFrom() != null && create.getFlightTo() != null && create.getJourneyDate() != null) {
            legs = List.of(com.voyra.crm.dto.VoucherLeg.builder().from(create.getFlightFrom()).to(create.getFlightTo())
                    .airline(create.getAirline()).flightNumber(create.getFlightNumber()).cabin(create.getFlightCabin())
                    .travelDate(create.getJourneyDate()).departTime(create.getFlightDepartTime()).arriveTime(create.getFlightArriveTime()).build());
        }
        List<com.voyra.crm.dto.VoucherLeg> trip;
        try {
            trip = com.voyra.crm.util.VoucherTrip.cleanLegs(legs);
        } catch (IllegalArgumentException e) {
            trip = List.of(); // the service's sectors don't make a ticket; the voucher's edit asks for them
        }
        List<com.voyra.crm.dto.VoucherLeg> flights = trip;
        bookingDocumentRepository.findById(documentId).ifPresent(d -> {
            d.setTravellers(travellers.isEmpty() ? null : travellers);
            d.setLegs(flights.isEmpty() ? null : flights);
            d.setDetails(com.voyra.crm.util.VoucherTrip.details(flights, d.getDetails()));
            bookingDocumentRepository.save(d);
        });
    }

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
        // Foreign purchase price or flat markup: the booking's money is their base value.
        com.voyra.crm.util.FxMath.Pricing pricing = com.voyra.crm.util.FxMath.resolve(request, baseCurrency.code());
        if (pricing.netCost() != null) {
            request.setNetCost(pricing.netCost());
        }
        if (pricing.markupValue() != null) {
            request.setMarkupValue(pricing.markupValue());
        }
        BookingCreateRequest create = BookingLogRequestMapper.toCreateRequest(request, service, members, vendorName);

        String documentId = UniqueIdResolver.resolve(bookingDocumentRepository::existsById);
        String tenantId = SecurityContextUtil.getCurrentUserOrThrow().tenantId();
        String fileKey = fileStorageService.store(tenantId, DOCUMENT_FILE_CATEGORY, documentId, voucher);
        try {
            BookingResponse booking = bookingService.logBooking(create, documentId, fileKey,
                    voucher.getOriginalFilename(), voucher.getContentType(),
                    BookingLogRequestMapper.voucherTypeFor(create.getType()), request.getReference().trim(), pricing);
            nameTheTrip(documentId, create, members);
            log.info("Booking logged with voucher: bookingId={}, serviceId={}", booking.getId(), request.getServiceId());
            return booking;
        } catch (RuntimeException e) {
            fileStorageService.delete(fileKey);
            throw e;
        }
    }
}
