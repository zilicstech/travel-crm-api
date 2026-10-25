package com.voyra.crm.service.booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.dto.BookingTimelineLine;
import com.voyra.crm.dto.HotelStaySummary;
import com.voyra.crm.dto.ItinerarySegment;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.dto.PaxSummary;
import com.voyra.crm.dto.PlatformAdminBookingResponse;
import com.voyra.crm.dto.PlatformBookingEventResponse;
import com.voyra.crm.dto.PlatformBookingResponse;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.Client;
import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.entity.PlatformBookingEvent;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.enums.PlatformBookingStatus;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.ClientRepository;
import com.voyra.crm.repository.PlatformBookingEventRepository;
import com.voyra.crm.repository.PlatformBookingRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.SecurityContextUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Read side of platform bookings: the agency view (hand-built, no Voyra money or supplier ids) and the admin view. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlatformBookingQueryService {

    /** Allow-list: an event type with no entry here is simply not shown to the agency. */
    private static final Map<String, String> AGENCY_EVENT_TEXT = Map.ofEntries(
            Map.entry("REVIEWED", "Fare reviewed"),
            Map.entry("HELD", "Wallet amount reserved"),
            Map.entry("BOOKING", "Sent to the airline"),
            Map.entry("REPRICED", "The fare changed while booking"),
            Map.entry("BOOK_NO_ANSWER", "The airline has not answered yet, we are checking"),
            Map.entry("UNKNOWN", "Waiting for the airline to confirm"),
            Map.entry("TICKETED", "Ticket issued"),
            Map.entry("LATE_TICKET", "The airline issued the ticket late"),
            Map.entry("FAILED", "Booking failed"),
            Map.entry("RESOLVED", "Updated by Voyra support"),
            Map.entry("ABORTED", "Booking dropped before it was sent"),
            Map.entry("CANCEL_REQUESTED", "Cancellation requested"),
            Map.entry("CANCEL_ACCEPTED", "Cancellation accepted by the airline"),
            Map.entry("CANCEL_NO_ANSWER", "Cancellation is pending with the airline"),
            Map.entry("CANCELLED", "Booking cancelled"));

    /** Hotel wording where the flight text would say ticket or airline. */
    private static final Map<String, String> HOTEL_EVENT_TEXT = Map.of(
            "REVIEWED", "Rate reviewed",
            "REPRICED", "The rate changed while booking",
            "TICKETED", "Booking confirmed",
            "LATE_TICKET", "The hotel confirmed the booking late");

    private final PlatformBookingRepository bookingRepository;
    private final PlatformBookingEventRepository eventRepository;
    private final TenantRepository tenantRepository;
    private final AgentRepository agentRepository;
    private final ClientRepository clientRepository;
    private final PaxCodec paxCodec;
    private final ObjectMapper objectMapper;

    /** Owner and accountant see the agency's bookings; an agent sees only the ones they made. */
    public PagedResponse<PlatformBookingResponse> list(Integer page, Integer size) {
        CustomUserPrincipal p = SecurityContextUtil.getCurrentUserOrThrow();
        Pageable pageable = newestFirst(page, size);
        Page<PlatformBooking> rows = p.isAgent()
                ? bookingRepository.findByTenantIdAndBookedBy(p.tenantId(), p.userId(), pageable)
                : bookingRepository.findByTenantId(p.tenantId(), pageable);
        List<PlatformBookingResponse> views = agencyViews(rows.getContent(), false);
        return paged(rows, views);
    }

    /** Another agency's id, or an agent's colleague's, is simply absent (404). */
    public PlatformBookingResponse get(String id) {
        CustomUserPrincipal p = SecurityContextUtil.getCurrentUserOrThrow();
        PlatformBooking b = bookingRepository.findByIdAndTenantId(id, p.tenantId())
                .filter(x -> !p.isAgent() || p.userId().equals(x.getBookedBy()))
                .orElseThrow(() -> new NotFoundException("Booking not found"));
        return view(b);
    }

    /** Detail view of a row the caller's tenant already owns (a service call just returned it). */
    public PlatformBookingResponse view(PlatformBooking b) {
        return agencyViews(List.of(b), true).get(0);
    }

    public PagedResponse<PlatformAdminBookingResponse> adminList(Integer page, Integer size, PlatformBookingStatus status, String tenantId) {
        Specification<PlatformBooking> spec = (root, q, cb) -> {
            List<jakarta.persistence.criteria.Predicate> ps = new ArrayList<>();
            if (status != null) {
                ps.add(cb.equal(root.get("status"), status));
            }
            if (tenantId != null && !tenantId.isBlank()) {
                ps.add(cb.equal(root.get("tenantId"), tenantId));
            }
            return cb.and(ps.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        Page<PlatformBooking> rows = bookingRepository.findAll(spec, newestFirst(page, size));
        return paged(rows, adminViews(rows.getContent(), false));
    }

    public PlatformAdminBookingResponse adminGet(String id) {
        return adminView(bookingRepository.findById(id).orElseThrow(() -> new NotFoundException("Booking not found")));
    }

    public PlatformAdminBookingResponse adminView(PlatformBooking b) {
        return adminViews(List.of(b), true).get(0);
    }

    private static Pageable newestFirst(Integer page, Integer size) {
        Pageable p = com.voyra.crm.util.PageRequestUtil.resolve(page == null ? 0 : page, size);
        return PageRequest.of(p.getPageNumber(), p.getPageSize(), Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    private static <D> PagedResponse<D> paged(Page<?> source, List<D> content) {
        return PagedResponse.<D>builder().content(content).page(source.getNumber()).size(source.getSize())
                .totalElements(source.getTotalElements()).totalPages(source.getTotalPages()).build();
    }

    private List<PlatformBookingResponse> agencyViews(List<PlatformBooking> rows, boolean detail) {
        Map<String, String> agentNames = names(rows, PlatformBooking::getBookedBy,
                agentRepository::findAllById, Agent::getId, Agent::getName);
        Map<String, String> clientNames = names(rows, PlatformBooking::getClientId,
                clientRepository::findAllById, Client::getId, Client::getName);
        return rows.stream().<PlatformBookingResponse>map(b -> PlatformBookingResponse.builder()
                .id(b.getId()).product(b.getProduct()).status(b.getStatus()).pnr(b.getPnr()).ticketNumbers(b.getTicketNumbers())
                .displayedFare(b.getDisplayed()).agencyEarns(agencyEarns(b)).itinerary(itinerary(b)).hotel(hotel(b)).passengers(passengers(b))
                .bookedByName(agentNames.get(b.getBookedBy())).clientName(clientNames.get(b.getClientId()))
                .createdAt(b.getCreatedAt()).ticketedAt(b.getTicketedAt()).cancelRefund(b.getCancelRefund())
                .supplierMessage(AgencySupplierMessage.of(b)).mirrorStatus(b.getMirrorStatus()).tenantBookingId(b.getTenantBookingId())
                .timeline(detail ? timeline(b) : null)
                .build()).toList();
    }

    private List<PlatformAdminBookingResponse> adminViews(List<PlatformBooking> rows, boolean detail) {
        Map<String, String> agencyNames = names(rows, PlatformBooking::getTenantId,
                tenantRepository::findAllById, Tenant::getId, Tenant::getAgencyName);
        Map<String, String> agentNames = names(rows, PlatformBooking::getBookedBy,
                agentRepository::findAllById, Agent::getId, Agent::getName);
        return rows.stream().<PlatformAdminBookingResponse>map(b -> PlatformAdminBookingResponse.builder()
                .id(b.getId()).product(b.getProduct()).status(b.getStatus()).pnr(b.getPnr()).ticketNumbers(b.getTicketNumbers())
                .displayedFare(b.getDisplayed()).agencyEarns(agencyEarns(b)).itinerary(itinerary(b)).hotel(hotel(b)).passengers(passengers(b))
                .bookedByName(agentNames.get(b.getBookedBy()))
                .createdAt(b.getCreatedAt()).ticketedAt(b.getTicketedAt()).cancelRefund(b.getCancelRefund())
                .supplierMessage(AgencySupplierMessage.of(b)).supplierMessageRaw(b.getSupplierMessage())
                .mirrorStatus(b.getMirrorStatus()).tenantBookingId(b.getTenantBookingId())
                .tenantId(b.getTenantId()).agencyName(agencyNames.get(b.getTenantId())).environment(b.getEnvironment())
                .gross(b.getGross()).base(b.getBase()).discountPct(b.getDiscountPct()).voyraEarned(b.getVoyraEarned())
                .voyraEarnedSource(b.getVoyraEarnedSource()).voyraEarnedActual(b.getVoyraEarnedActual()).agencyCommission(b.getAgencyCommission()).tds(b.getTds())
                .voyraNet(b.getVoyraNet())
                .events(detail ? eventRepository.findByPlatformBookingIdOrderByCreatedAtAsc(b.getId()).stream()
                        .map(e -> new PlatformBookingEventResponse(e.getType(), e.getPayload(), e.getCreatedAt())).toList() : null)
                .build()).toList();
    }

    /**
     * One batched lookup of display names for the distinct, non-null ids on {@code rows} (no N+1). Always a HashMap:
     * a client-less or booker-less row looks up {@code null}, which an immutable {@code Map.of()} rejects with an NPE.
     */
    private static <T> Map<String, String> names(List<PlatformBooking> rows, Function<PlatformBooking, String> idOf,
                                                 Function<Collection<String>, ? extends Iterable<T>> loader,
                                                 Function<T, String> id, Function<T, String> name) {
        Map<String, String> out = new java.util.HashMap<>();
        List<String> ids = rows.stream().map(idOf).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return out;
        }
        loader.apply(ids).forEach(t -> out.put(id.apply(t), name.apply(t)));
        return out;
    }

    private static BigDecimal agencyEarns(PlatformBooking b) {
        return b.getAgencyCommission() == null ? null
                : b.getAgencyCommission().subtract(b.getTds() == null ? BigDecimal.ZERO : b.getTds());
    }

    @SuppressWarnings("unchecked")
    private List<PaxSummary> passengers(PlatformBooking b) {
        if (b.getPaxEnc() == null) {
            return List.of();
        }
        Object list = paxCodec.decrypt(b.getPaxEnc(), Map.class).get("passengers");
        if (!(list instanceof List<?> pax)) {
            return List.of();
        }
        return pax.stream().map(o -> (Map<String, Object>) o)
                .map(m -> new PaxSummary(java.util.stream.Stream.of(m.get("title"), m.get("firstName"), m.get("lastName"))
                        .filter(Objects::nonNull).map(Object::toString).filter(s -> !s.isBlank()).collect(Collectors.joining(" ")),
                        String.valueOf(m.get("type")))).toList();
    }

    /** The stay from a HOTEL row's itinerary {@code {hotel, stay, rooms}}; null for flights. Missing parts stay null. */
    HotelStaySummary hotel(PlatformBooking b) {
        if (b.getProduct() != PricingProduct.HOTEL || b.getItinerary() == null) {
            return null;
        }
        JsonNode it = objectMapper.valueToTree(b.getItinerary());
        JsonNode stay = it.path("stay");
        JsonNode rooms = it.path("rooms");
        return new HotelStaySummary(it.path("hotel").path("name").asText(null), it.path("hotel").path("city").asText(null),
                date(stay.path("checkIn")), date(stay.path("checkOut")), stay.path("nights").isNumber() ? stay.path("nights").asInt() : null,
                rooms.isArray() ? rooms.size() : null, rooms.path(0).path("mealBasis").asText(null));
    }

    private static LocalDate date(JsonNode n) {
        try {
            return n.isTextual() ? LocalDate.parse(n.asText()) : null;
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /** Pulls the legs out of the supplier itinerary by Tripjack's {@code sI} segments; anything unrecognised yields no legs. */
    public List<ItinerarySegment> itinerary(PlatformBooking b) {
        if (b.getItinerary() == null) {
            return List.of();
        }
        List<ItinerarySegment> out = new ArrayList<>();
        for (JsonNode legs : objectMapper.valueToTree(b.getItinerary()).findValues("sI")) {
            for (JsonNode s : legs) {
                String code = s.path("fD").path("aI").path("code").asText("");
                out.add(new ItinerarySegment(s.path("da").path("code").asText(null), s.path("aa").path("code").asText(null),
                        s.path("dt").asText(null), s.path("at").asText(null), s.path("fD").path("aI").path("name").asText(null),
                        code.isEmpty() ? s.path("fD").path("fN").asText(null) : code + "-" + s.path("fD").path("fN").asText("")));
            }
        }
        return out;
    }

    private List<BookingTimelineLine> timeline(PlatformBooking b) {
        boolean hotel = b.getProduct() == PricingProduct.HOTEL;
        List<BookingTimelineLine> out = new ArrayList<>();
        for (PlatformBookingEvent e : eventRepository.findByPlatformBookingIdOrderByCreatedAtAsc(b.getId())) {
            String text = AGENCY_EVENT_TEXT.get(e.getType());
            if (text != null && hotel) {
                text = HOTEL_EVENT_TEXT.getOrDefault(e.getType(), text.replace("airline", "hotel"));
            }
            if (text != null) {
                out.add(new BookingTimelineLine(e.getCreatedAt(), text));
            }
        }
        return out;
    }
}
