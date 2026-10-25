package com.voyra.crm.service.booking;

import com.voyra.crm.dto.BookingCancelRequest;
import com.voyra.crm.dto.BookingCreateRequest;
import com.voyra.crm.dto.BookingPassengerRequest;
import com.voyra.crm.dto.BookingSectorRequest;
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
import com.voyra.crm.entity.MarkupDefault;
import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.entity.Vendor;
import com.voyra.crm.enums.BookingDocumentType;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.CancellationType;
import com.voyra.crm.enums.MarkupMode;
import com.voyra.crm.enums.PaxType;
import com.voyra.crm.enums.PaymentMode;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.enums.SupplierInvoiceStatus;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.BookingDocumentRepository;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.repository.LeadServiceRepository;
import com.voyra.crm.repository.MarkupDefaultRepository;
import com.voyra.crm.repository.VendorRepository;
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
import com.voyra.crm.util.VoucherTrip;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    static final String VOYRA_VENDOR = "Voyra Platform";
    private static final String INR = "INR";

    private final BookingService bookingService;
    private final BookingRepository bookingRepository;
    private final BookingDocumentRepository bookingDocumentRepository;
    private final BookingCancellationService cancellationService;
    private final SupplierInvoiceService supplierInvoiceService;
    private final SupplierPaymentService supplierPaymentService;
    private final VendorRepository vendorRepository;
    private final AgentRepository agentRepository;
    private final LeadServiceRepository leadServiceRepository;
    private final LeadRepository leadRepository;
    private final MarkupDefaultRepository markupDefaultRepository;
    private final ExchangeRateProvider exchangeRateProvider;
    private final BaseCurrency baseCurrency;

    /**
     * The mirror booking (spec 6): CONFIRMED with vendor Voyra Platform, one E_TICKET bought in INR at the displayed fare
     * with the fare's base/tax split and the agency's default flight markup, and its supplier bill paid from the Voyra
     * wallet. Returns the existing mirror when there is one, so a retry never writes a second.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String create(PlatformBooking pb, List<ItinerarySegment> segments, List<PaxInput> passengers) {
        Optional<Booking> existing = bookingRepository.findByPlatformBookingId(pb.getId());
        if (existing.isPresent()) {
            return existing.get().getId();
        }
        Vendor vendor = vendorRepository.findByNameIgnoreCase(VOYRA_VENDOR).filter(v -> Boolean.TRUE.equals(v.getIsActive()))
                .orElseThrow(() -> new IllegalStateException("This agency has no active " + VOYRA_VENDOR + " vendor"));
        List<VoucherLeg> legs = VoucherTrip.cleanLegs(segments.stream().map(PlatformBookingMirrorTenantService::leg).toList());
        Map<String, String> trip = Optional.ofNullable(VoucherTrip.details(legs, null)).orElse(Map.of());
        LocalDate firstFlight = VoucherTrip.firstDate(legs);
        String base = baseCurrency.code();
        // the ticket was bought in rupees; a non-INR agency needs today's rate, and a missing one fails the mirror, never the ticket
        BigDecimal rate = exchangeRateProvider.resolve(INR, base, LocalDate.now()).rate();
        FxMath.Converted purchase = FxMath.toBase(INR, pb.getDisplayed(), rate, base);
        MarkupDefault markup = markupDefaultRepository.findById(ServiceType.FLIGHT).orElse(null);

        BookingCreateRequest request = new BookingCreateRequest();
        request.setType(BookingType.FLIGHT);
        request.setClientId(pb.getClientId());
        request.setAgentId(agentFor(pb));
        request.setServiceId(pb.getServiceId());
        request.setLeadId(pb.getLeadId());
        request.setVendorId(vendor.getId());
        request.setSupplier(vendor.getName());
        request.setPnr(pb.getPnr());
        request.setTicketNo(ticketNo(pb.getTicketNumbers()));
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
        request.setNotes("Booked with Voyra - managed from Platform Bookings");
        request.setNetCost(purchase.base());
        request.setMarkupMode(markup != null && markup.getMode() != null ? markup.getMode() : MarkupMode.PERCENT);
        request.setMarkupValue(markup != null && markup.getValue() != null ? markup.getValue() : BigDecimal.ZERO);
        request.setOverrideCreditLimit(true); // the ticket is already bought and paid for
        request.setPassengers(passengers.stream().map(p -> passenger(p, legs, firstFlight)).toList());

        String documentId = UniqueIdResolver.resolve(bookingDocumentRepository::existsById);
        String bookingId = bookingService.logBooking(request, documentId, null, null, null, BookingDocumentType.E_TICKET,
                pb.getPnr(), new FxMath.Pricing(purchase.base(), request.getMarkupValue(), purchase, null)).getId();

        BookingDocument ticket = bookingDocumentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalStateException("The mirror's e-ticket was not saved"));
        ticket.setPurchaseCurrency(INR);
        ticket.setPurchasePrice(pb.getDisplayed());
        ticket.setPurchaseRate(rate);
        ticket.setCharges(charges(pb));
        ticket.setIssuedDate(LocalDate.now());
        ticket.setLegs(legs.isEmpty() ? null : legs);
        ticket.setTravellers(passengers.isEmpty() ? null : passengers.stream()
                .map(p -> VoucherTraveller.builder().memberId(blankToNull(p.getMemberId())).name(name(p))
                        .paxType(paxType(p, firstFlight)).build()).toList());
        ticket.setDetails(VoucherTrip.details(legs, ticket.getDetails()));
        bookingDocumentRepository.save(ticket);

        Booking booking = bookingRepository.findById(bookingId).orElseThrow(() -> new NotFoundException("Booking not found: " + bookingId));
        booking.setPlatformBookingId(pb.getId());
        bookingRepository.save(booking);
        payFromVoyraWallet(ticket.getSupplierInvoiceId(), vendor.getId(), rate, pb);
        return bookingId;
    }

    /**
     * Cancels the mirror through the existing cancel (spec 7.5): what Voyra kept ({@code displayed - refund}) is the
     * supplier's charge, and the refund already in the agency's wallet is recorded against "Voyra Wallet (advance to
     * supplier)". A mirror already cancelled is left alone.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cancel(String bookingId, PlatformBooking pb) {
        Booking booking = bookingRepository.findById(bookingId).orElseThrow(() -> new NotFoundException("Booking not found: " + bookingId));
        if (booking.getBookingStatus() == BookingStatus.CANCELLED) {
            return;
        }
        BigDecimal refund = pb.getCancelRefund();
        if (refund == null) {
            throw new IllegalStateException("Platform booking " + pb.getId() + " was cancelled without a refund figure - settle the mirror by hand");
        }
        BookingCancelRequest request = new BookingCancelRequest();
        request.setType(CancellationType.CANCELLATION);
        request.setReason("Cancelled with Voyra");
        request.setAirlineCharge(BigDecimal.ZERO);
        request.setSupplierCharge(pb.getDisplayed().subtract(refund).max(BigDecimal.ZERO));
        request.setAgencyFee(BigDecimal.ZERO);
        cancellationService.cancel(bookingId, request);
        if (refund.signum() > 0) {
            cancellationService.recordVoyraWalletRefund(bookingId, refund);
        }
    }

    /** Approves the e-ticket's auto-drafted bill and pays it in full with {@link PaymentMode#VOYRA_WALLET} (Cr 1310). */
    private void payFromVoyraWallet(String billId, String vendorId, BigDecimal rate, PlatformBooking pb) {
        if (billId == null) {
            throw new IllegalStateException("The mirror's supplier bill was not drafted");
        }
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

    /** Booking user if an agent, else the agent picked as issuer, else the lead's agent (spec 6). */
    private String agentFor(PlatformBooking pb) {
        String serviceAgent = pb.getServiceId() == null ? null
                : leadServiceRepository.findById(pb.getServiceId()).map(LeadService::getAssignedAgentId).orElse(null);
        String leadAgent = pb.getLeadId() == null ? null : leadRepository.findById(pb.getLeadId()).map(Lead::getCreatedBy).orElse(null);
        return Stream.of(pb.getBookedBy(), pb.getIssuedByAgentId(), serviceAgent, leadAgent)
                .filter(Objects::nonNull)
                .filter(id -> agentRepository.findByIdAndTenantId(id, pb.getTenantId()).isPresent())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No agent to own the CRM booking of platform booking " + pb.getId()
                        + " - it needs an issuing agent"));
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

    /** All the ticket numbers within the 50-character column; every one stays on the platform booking. */
    private static String ticketNo(List<String> numbers) {
        if (numbers == null || numbers.isEmpty()) {
            return null;
        }
        String all = String.join(", ", numbers);
        return all.length() <= 50 ? all : all.substring(0, 50);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
