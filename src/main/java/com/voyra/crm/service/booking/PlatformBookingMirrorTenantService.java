package com.voyra.crm.service.booking;

import com.voyra.crm.dto.BookingCancelRequest;
import com.voyra.crm.dto.BookingCreateRequest;
import com.voyra.crm.dto.BookingPassengerRequest;
import com.voyra.crm.dto.BookingSectorRequest;
import com.voyra.crm.dto.HotelStaySummary;
import com.voyra.crm.dto.ItinerarySegment;
import com.voyra.crm.dto.PaxInput;
import com.voyra.crm.dto.SupplierInvoiceResponse;
import com.voyra.crm.dto.SupplierPaymentRequest;
import com.voyra.crm.dto.VoucherLeg;
import com.voyra.crm.dto.VoucherTraveller;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.BookingDocument;
import com.voyra.crm.entity.Lead;
import com.voyra.crm.entity.LeadService;
import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.entity.SupplierInvoice;
import com.voyra.crm.entity.Vendor;
import com.voyra.crm.enums.BookingDocumentType;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.CancellationType;
import com.voyra.crm.enums.MarkupMode;
import com.voyra.crm.enums.PaxType;
import com.voyra.crm.enums.PaymentMode;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.enums.SupplierInvoiceStatus;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.repository.BookingDocumentRepository;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.LeadServiceRepository;
import com.voyra.crm.repository.SupplierInvoiceRepository;
import com.voyra.crm.repository.VendorRepository;
import com.voyra.crm.security.SystemActor;
import com.voyra.crm.service.BaseCurrency;
import com.voyra.crm.service.BookingCancellationService;
import com.voyra.crm.service.BookingService;
import com.voyra.crm.service.ExchangeRateProvider;
import com.voyra.crm.service.SupplierInvoiceService;
import com.voyra.crm.service.SupplierPaymentService;
import com.voyra.crm.util.FxMath;
import com.voyra.crm.util.PaxTypeCalculator;
import com.voyra.crm.util.UniqueIdResolver;
import com.voyra.crm.util.VoucherCharges;
import com.voyra.crm.util.VoucherDetails;
import com.voyra.crm.util.VoucherTrip;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The tenant-schema half of the CRM mirror: called by {@link PlatformBookingMirrorService} inside a
 * {@code SystemActor} scope, each method in its own {@code REQUIRES_NEW} transaction so the connection is borrowed with
 * the agency's search_path (blueprint 3.5) and a failure rolls the whole mirror step back. Goes through the existing
 * booking services, so the CRM's own rules (supplier bill, ledger, credit notes, GST) apply to the mirror as to any booking.
 */
@Service
@RequiredArgsConstructor
public class PlatformBookingMirrorTenantService {

    private static final String INR = "INR";
    private static final java.util.Set<BookingStatus> CONFIRMED = java.util.EnumSet.of(BookingStatus.CONFIRMED, BookingStatus.COMPLETED);
    private static final java.util.Set<BookingDocumentType> MIRROR_VOUCHERS = java.util.EnumSet.of(BookingDocumentType.E_TICKET, BookingDocumentType.HOTEL_VOUCHER);

    private final BookingService bookingService;
    private final BookingRepository bookingRepository;
    private final BookingDocumentRepository bookingDocumentRepository;
    private final BookingCancellationService cancellationService;
    private final SupplierInvoiceService supplierInvoiceService;
    private final SupplierPaymentService supplierPaymentService;
    private final VendorRepository vendorRepository;
    private final LeadServiceRepository leadServiceRepository;
    private final SupplierInvoiceRepository supplierInvoiceRepository;
    private final PlatformBookingQueryService queryService;
    private final ExchangeRateProvider exchangeRateProvider;
    private final BaseCurrency baseCurrency;
    private final com.voyra.crm.repository.ClientRepository clientRepository;
    private final com.voyra.crm.service.LeadService leadAccess;
    private final com.voyra.crm.service.ServiceInstanceService serviceAccess;
    private final com.voyra.crm.repository.AgentRepository agentRepository;

    /**
     * Phase 1: the CRM booking exists from the moment the booking is sent. Under a lock on the platform booking id (so a
     * second sync waits, then finds this one's row) it returns the existing mirror, or writes it PENDING through
     * {@link BookingService#createVoyraPending}: the trip, the client, agent, lead and service, vendor Voyra Platform, and
     * one voucher bought in INR at the displayed fare (in the agency's base at today's rate, the only FX lookup) with the
     * fare's base/tax split, priced at the published fare plus the current markup. No reference until it is ticketed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String ensure(PlatformBooking pb, List<ItinerarySegment> segments, HotelStaySummary stay, List<PaxInput> passengers) {
        bookingRepository.lockPlatformBooking(pb.getId());
        Optional<Booking> existing = bookingRepository.findByPlatformBookingId(pb.getId());
        if (existing.isPresent()) {
            return existing.get().getId(); // a retry never needs the stay again
        }
        boolean hotel = pb.getProduct() == PricingProduct.HOTEL;
        BookingCreateRequest request = hotel ? hotelRequest(pb, stay, passengers) : flightRequest(segments, passengers);
        Vendor vendor = vendorRepository.findByIsSystemTrueAndIsActiveTrue()
                .orElseThrow(() -> new IllegalStateException("This agency has no active Voyra Platform vendor"));
        String base = baseCurrency.code();
        // bought in rupees; a non-INR agency needs today's rate, and a missing one fails the mirror, never the booking
        BigDecimal rate = exchangeRateProvider.resolve(INR, base, LocalDate.now()).rate();
        BigDecimal net = FxMath.toBase(INR, pb.getDisplayed(), rate, base).base();

        request.setClientId(pb.getClientId());
        request.setAgentId(agentFor(pb));
        request.setServiceId(pb.getServiceId());
        request.setLeadId(pb.getLeadId());
        request.setVendorId(vendor.getId());
        request.setSupplier(vendor.getName());
        request.setNotes("Booked with Voyra");
        request.setOverrideCreditLimit(true); // already bought and paid for

        BookingDocument voucher = BookingDocument.builder()
                .id(UniqueIdResolver.resolve(bookingDocumentRepository::existsById))
                .docType(hotel ? BookingDocumentType.HOTEL_VOUCHER : BookingDocumentType.E_TICKET)
                .netCost(net)
                .markupMode(MarkupMode.FLAT)
                .markupValue(markupBase(pb, queryService.currentMarkup(pb.getId()), rate, base))
                .purchaseCurrency(INR)
                .purchasePrice(pb.getDisplayed())
                .purchaseRate(rate)
                .charges(charges(pb))
                .details(hotel ? hotelDetails(request) : null)
                .createdAt(LocalDateTime.now())
                .createdBy(SystemActor.USER_ID)
                .build();
        return bookingService.createVoyraPending(request, voucher, pb.getId());
    }

    /** The trip of a flight mirror, as a logged flight booking carries it; the PNR and ticket numbers come with the ticket. */
    private static BookingCreateRequest flightRequest(List<ItinerarySegment> segments, List<PaxInput> passengers) {
        List<VoucherLeg> legs = legs(segments);
        Map<String, String> trip = Optional.ofNullable(VoucherTrip.details(legs, null)).orElse(Map.of());
        BookingCreateRequest request = new BookingCreateRequest();
        request.setType(BookingType.FLIGHT);
        request.setAirline(trip.get("airline"));
        request.setFlightNumber(trip.get("flightNumber"));
        request.setFlightFrom(trip.get("flightFrom"));
        request.setFlightTo(trip.get("flightTo"));
        request.setFlightDepartTime(trip.get("flightDepartTime"));
        request.setFlightArriveTime(trip.get("flightArriveTime"));
        request.setJourneyDate(date(trip.get("journeyDate")));
        request.setReturnDate(date(trip.get("returnDate")));
        request.setTripType(legs.isEmpty() ? null : request.getReturnDate() != null ? "Round Trip" : "One Way");
        request.setDestination(trip.getOrDefault("flightTo", "Flight"));
        LocalDate firstFlight = VoucherTrip.firstDate(legs);
        request.setPassengers(passengers.stream().map(p -> passenger(p, legs, firstFlight)).toList());
        return request;
    }

    /** The stay of a hotel mirror (hotel, city, check-in/out, rooms, room type, meal basis); the confirmation number comes with the ticket. */
    private static BookingCreateRequest hotelRequest(PlatformBooking pb, HotelStaySummary stay, List<PaxInput> passengers) {
        if (stay == null || stay.checkIn() == null || stay.checkOut() == null) {
            throw new IllegalStateException("Platform booking " + pb.getId() + " has no stay to mirror");
        }
        BookingCreateRequest request = new BookingCreateRequest();
        request.setType(BookingType.HOTEL);
        request.setHotelName(stay.hotelName());
        request.setHotelCity(stay.city());
        request.setHotelCheckIn(stay.checkIn());
        request.setHotelCheckOut(stay.checkOut());
        request.setHotelRooms(stay.rooms());
        request.setHotelRoomType(roomName(pb));
        request.setHotelBoardBasis(stay.mealBasis());
        request.setJourneyDate(stay.checkIn()); // as a logged hotel booking: the stay is the journey
        request.setReturnDate(stay.checkOut());
        request.setDestination(Stream.of(stay.city(), stay.hotelName()).filter(s -> s != null && !s.isBlank()).findFirst().orElse("Hotel"));
        request.setPassengers(passengers.stream().map(p -> passenger(p, List.of(), stay.checkIn())).toList());
        return request;
    }

    private static Map<String, String> hotelDetails(BookingCreateRequest r) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("hotelName", r.getHotelName());
        details.put("hotelCity", r.getHotelCity());
        details.put("hotelCheckIn", r.getHotelCheckIn().toString());
        details.put("hotelCheckOut", r.getHotelCheckOut().toString());
        details.put("hotelRooms", r.getHotelRooms() == null ? null : r.getHotelRooms().toString());
        details.put("hotelRoomType", r.getHotelRoomType());
        details.put("hotelBoardBasis", r.getHotelBoardBasis());
        return VoucherDetails.clean(BookingType.HOTEL, details);
    }

    /** The FLAT markup in base that makes the selling price the published fare plus {@code ticketMarkup} (spec Part 3). */
    private static BigDecimal markupBase(PlatformBooking pb, BigDecimal ticketMarkup, BigDecimal rate, String base) {
        BigDecimal net = FxMath.toBase(INR, pb.getDisplayed(), rate, base).base();
        return FxMath.toBase(INR, pb.getDisplayed().add(markupInr(pb, ticketMarkup)), rate, base).base().subtract(net);
    }

    /**
     * Set markup (spec Part 3): the CRM booking's price becomes the published fare plus {@code ticketMarkup}, converted at
     * the voucher's stored rate (ensure made the only FX lookup). A markup-only reprice through
     * {@link BookingService#repriceVoyraMarkup}; its refusals (cancelled, below the invoiced price) reach the caller.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void setMarkup(PlatformBooking pb, BigDecimal ticketMarkup) {
        String crmId = pb.getTenantBookingId();
        bookingService.repriceVoyraMarkup(crmId, markupBase(pb, ticketMarkup, voucherRate(crmId), baseCurrency.code()));
    }

    /** What the agency adds over the displayed fare, in rupees: its discount (published minus displayed) plus the ticket markup. */
    static BigDecimal markupInr(PlatformBooking pb, BigDecimal ticketMarkup) {
        BigDecimal discount = pb.getGross() == null ? BigDecimal.ZERO : pb.getGross().subtract(pb.getDisplayed());
        return discount.add(ticketMarkup).max(BigDecimal.ZERO);
    }

    /**
     * Phase 2: Voyra ticketed it. {@link BookingService#confirmVoyraTicket} (under the booking's row lock) sets the
     * reference and ticket numbers, describes the voucher (flight legs, travellers and details; a hotel's stay was set by
     * ensure) and drafts its bill; a bill still DRAFT is then approved and paid from the Voyra wallet at the voucher's
     * stored rate. A repeat finds the bill approved or paid and pays nothing.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ticket(String crmId, PlatformBooking pb, List<ItinerarySegment> segments, List<PaxInput> passengers) {
        boolean hotel = pb.getProduct() == PricingProduct.HOTEL;
        List<VoucherLeg> legs = hotel ? List.of() : legs(segments);
        LocalDate firstFlight = VoucherTrip.firstDate(legs);
        boolean wasConfirmed = CONFIRMED.contains(bookingRepository.findById(crmId).map(Booking::getBookingStatus).orElse(null));
        String billId = bookingService.confirmVoyraTicket(crmId, pb.getPnr(), ticketNo(pb.getTicketNumbers()), voucher -> {
            voucher.setIssuedDate(LocalDate.now());
            if (!hotel) {
                voucher.setLegs(legs.isEmpty() ? null : legs);
                voucher.setTravellers(passengers.isEmpty() ? null : passengers.stream()
                        .map(p -> VoucherTraveller.builder().memberId(blankToNull(p.getMemberId())).name(name(p))
                                .paxType(paxType(p, firstFlight)).build()).toList());
                voucher.setDetails(VoucherTrip.details(legs, voucher.getDetails()));
            }
        });
        SupplierInvoice bill = Optional.ofNullable(billId).flatMap(supplierInvoiceRepository::findById)
                .orElseThrow(() -> new IllegalStateException("The mirror's supplier bill was not drafted"));
        if (bill.getStatus() == SupplierInvoiceStatus.DRAFT) {
            payFromVoyraWallet(billId, bill.getVendorId(), voucherRate(crmId), pb);
        }
        // only the row this phase confirmed: a ticket downloaded while it was pending is priced now, and an older
        // confirmed row keeps its price until its markup is set
        if (!wasConfirmed && bookingRepository.findById(crmId).map(Booking::getBookingStatus).orElse(null) == BookingStatus.CONFIRMED) {
            bookingService.repriceVoyraMarkup(crmId, markupBase(pb, queryService.currentMarkup(pb.getId()), voucherRate(crmId), baseCurrency.code()));
        }
    }

    /** Phase 3: Voyra could not book it - CANCELLED with the fixed reason and no money (a no-op unless PENDING). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(String crmId) {
        bookingService.failVoyraBooking(crmId);
    }

    /**
     * Cancels the mirror through the existing cancel (spec 7.5): what Voyra kept ({@code displayed - refund}) is the
     * supplier's charge, and the refund already in the agency's wallet is recorded against "Voyra Wallet (advance to
     * supplier)". A mirror already cancelled is left alone.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cancel(String bookingId, PlatformBooking pb) {
        // the lock first: a second sync (another instance's repair job) waits here, then sees CANCELLED
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new NotFoundException("Booking not found: " + bookingId));
        if (booking.getBookingStatus() == BookingStatus.CANCELLED) {
            return;
        }
        if (pb.getCancelRefund() == null) {
            throw new IllegalStateException("Platform booking " + pb.getId() + " was cancelled without a refund figure - settle the mirror by hand");
        }
        // INR figures in the agency's base, at the voucher's own rate - as ensure bought it
        BigDecimal rate = voucherRate(bookingId);
        String base = baseCurrency.code();
        BigDecimal refund = FxMath.toBase(INR, pb.getCancelRefund(), rate, base).base();
        BookingCancelRequest request = new BookingCancelRequest();
        request.setType(CancellationType.CANCELLATION);
        request.setReason("Cancelled with Voyra");
        request.setAirlineCharge(BigDecimal.ZERO);
        // from the converted figures, so charge + refund is exactly the purchase the mirror booked (two roundings can drift a cent)
        request.setSupplierCharge(FxMath.toBase(INR, pb.getDisplayed(), rate, base).base().subtract(refund).max(BigDecimal.ZERO));
        request.setAgencyFee(BigDecimal.ZERO);
        cancellationService.cancel(bookingId, request);
        if (refund.signum() > 0) {
            cancellationService.recordVoyraWalletRefund(bookingId, refund);
        }
    }

    /** The rate the mirror's voucher was bought at (1 when none is stored). */
    private BigDecimal voucherRate(String bookingId) {
        return bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc(bookingId).stream()
                .filter(d -> MIRROR_VOUCHERS.contains(d.getDocType()) && d.getPurchaseRate() != null)
                .map(BookingDocument::getPurchaseRate).findFirst().orElse(BigDecimal.ONE);
    }

    /** Approves the voucher's auto-drafted bill and pays it in full with {@link PaymentMode#VOYRA_WALLET} (Cr 1310). */
    private void payFromVoyraWallet(String billId, String vendorId, BigDecimal rate, PlatformBooking pb) {
        SupplierInvoiceResponse bill = supplierInvoiceService.approveForBooking(billId, "Paid from the Voyra wallet");
        if (bill.getStatus() != SupplierInvoiceStatus.APPROVED) {
            throw new IllegalStateException("The mirror's supplier bill could not be approved (" + bill.getStatus() + ")");
        }
        SupplierPaymentRequest pay = new SupplierPaymentRequest();
        pay.setVendorId(vendorId);
        pay.setSupplierInvoiceId(billId);
        pay.setAmount(bill.getBalanceDue());
        pay.setPaymentMode(PaymentMode.VOYRA_WALLET);
        pay.setSettlementFxRate(rate);
        pay.setPaidOn(LocalDate.now());
        pay.setInstrumentRef(pb.getPnr());
        pay.setNotes("Paid from the Voyra wallet");
        supplierPaymentService.pay(pay);
    }

    /**
     * Refuses, before any money moves, a review naming a CRM client, lead or service that is not in the caller's tenant
     * (the repositories read the caller's schema) or that the caller may not reach: a missing one is 400, an agent's
     * out-of-reach lead or service 403 (the CRM's own lead and service rules). The lead must be the client's and the
     * service the lead's (or, with no lead, the client's), so the mirror lands on the records the agency picked.
     */
    @Transactional(readOnly = true)
    public void requireLinks(String clientId, String leadId, String serviceId) {
        String client = blankToNull(clientId);
        if (client != null && !clientRepository.existsById(client)) {
            throw new IllegalArgumentException("Client not found in this agency: " + client);
        }
        Lead lead = blankToNull(leadId) == null ? null : leadAccess.findAccessibleLead(leadId);
        if (lead != null && client != null && !client.equals(lead.getClientId())) {
            throw new IllegalArgumentException("This lead belongs to another client");
        }
        if (blankToNull(serviceId) != null) {
            LeadService service = leadServiceRepository.findById(serviceId)
                    .orElseThrow(() -> new IllegalArgumentException("Service not found in this agency: " + serviceId));
            if (lead != null && !lead.getId().equals(service.getLeadId())) {
                throw new IllegalArgumentException("This service belongs to another lead");
            }
            if (lead == null && client != null && !client.equals(service.getClientId())) {
                throw new IllegalArgumentException("This service belongs to another client");
            }
            serviceAccess.assertEditAccess(service);
        }
    }

    /**
     * Whoever booked owns the CRM booking: an agent, or the owner (tenant id), which the booking service resolves. A booker
     * deleted since (the lookup skips deleted agents) falls back to the owner, so the mirror never fails for good.
     */
    private String agentFor(PlatformBooking pb) {
        return agentRepository.findByIdAndTenantId(pb.getBookedBy(), pb.getTenantId()).isPresent() ? pb.getBookedBy() : pb.getTenantId();
    }

    /** The fare's base/tax split carried onto the ticket: the tax as quoted, the agency's discount off the basic. */
    static Map<String, BigDecimal> charges(PlatformBooking pb) {
        if (pb.getGross() == null || pb.getBase() == null || pb.getDisplayed() == null) {
            return null;
        }
        BigDecimal tax = pb.getGross().subtract(pb.getBase()).max(BigDecimal.ZERO);
        BigDecimal basic = pb.getDisplayed().subtract(tax);
        if (basic.signum() < 0) {
            return null;
        }
        Map<String, BigDecimal> charges = new LinkedHashMap<>();
        charges.put("basic", basic);
        charges.put("otherTax", tax);
        return VoucherCharges.clean(charges);
    }

    static PaxType paxType(PaxInput p, LocalDate firstFlight) {
        if (p.getDob() != null && firstFlight != null) {
            return PaxTypeCalculator.paxTypeAt(p.getDob(), firstFlight);
        }
        try {
            return p.getType() == null ? PaxType.UNKNOWN : PaxType.valueOf(p.getType());
        } catch (IllegalArgumentException e) {
            return PaxType.UNKNOWN;
        }
    }

    private static BookingPassengerRequest passenger(PaxInput p, List<VoucherLeg> legs, LocalDate firstFlight) {
        BookingPassengerRequest r = new BookingPassengerRequest();
        r.setPassengerName(name(p));
        r.setPaxType(paxType(p, firstFlight));
        r.setFareAmount(BigDecimal.ZERO); // the supplier prices the booking, not each traveller
        r.setTaxAmount(BigDecimal.ZERO);
        r.setSectors(legs.stream().map(l -> {
            BookingSectorRequest s = new BookingSectorRequest();
            s.setSectorFrom(l.getFrom());
            s.setSectorTo(l.getTo());
            s.setFlightNumber(l.getFlightNumber());
            s.setTravelDate(l.getTravelDate());
            s.setCabinClass(l.getCabin());
            return s;
        }).toList());
        return r;
    }

    private static List<VoucherLeg> legs(List<ItinerarySegment> segments) {
        return VoucherTrip.cleanLegs(segments.stream().map(PlatformBookingMirrorTenantService::leg).toList());
    }

    /** A supplier segment's "2026-12-02T09:00" times become the leg's date and HH:mm. */
    private static VoucherLeg leg(ItinerarySegment s) {
        return VoucherLeg.builder().from(s.getFrom()).to(s.getTo()).airline(s.getAirline()).flightNumber(s.getFlightNumber())
                .travelDate(date(s.getDeparture())).departTime(time(s.getDeparture())).arriveTime(time(s.getArrival())).build();
    }

    private static LocalDate date(String iso) {
        return iso == null || iso.length() < 10 ? null : LocalDate.parse(iso.substring(0, 10));
    }

    private static String time(String iso) {
        return iso == null || iso.length() < 16 ? null : iso.substring(11, 16);
    }

    private static String name(PaxInput p) {
        return Stream.of(p.getTitle(), p.getFirstName(), p.getLastName())
                .filter(s -> s != null && !s.isBlank()).collect(Collectors.joining(" "));
    }

    /** Whole ticket numbers within the 50-character column, then "…"; every one stays on the platform booking. */
    static String ticketNo(List<String> numbers) {
        if (numbers == null || numbers.isEmpty()) {
            return null;
        }
        String all = String.join(", ", numbers);
        if (all.length() <= 50) {
            return all;
        }
        StringBuilder shown = new StringBuilder();
        for (String n : numbers) {
            String next = shown.isEmpty() ? n : shown + ", " + n;
            if (next.length() > 49) {
                break;
            }
            shown.setLength(0);
            shown.append(next);
        }
        // shortcut: a single number over 49 characters (none are) is cut; the platform booking keeps it whole
        return (shown.isEmpty() ? numbers.get(0).substring(0, 49) : shown.toString()) + "…";
    }

    /** The reviewed room's name (every room of one option shares it), or null. */
    private static String roomName(PlatformBooking pb) {
        Object rooms = pb.getItinerary() == null ? null : pb.getItinerary().get("rooms");
        return rooms instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> room && room.get("roomName") != null
                ? room.get("roomName").toString() : null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
