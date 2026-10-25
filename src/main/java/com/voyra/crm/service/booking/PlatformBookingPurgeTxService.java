package com.voyra.crm.service.booking;

import com.voyra.crm.dto.ItinerarySegment;
import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.entity.PlatformBookingEvent;
import com.voyra.crm.enums.PlatformBookingStatus;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.repository.PlatformBookingEventRepository;
import com.voyra.crm.repository.PlatformBookingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * DPDP purge of ONE platform booking, in its own transaction under the row lock: status and age are re-checked after the lock,
 * so a booking that moved on (TICKETED to CANCELLING) between the job's scan and this call is left alone.
 * Money, supplier ids, PNR and statuses stay; only passenger data goes. Public schema only, never a tenant schema.
 */
@Service
@RequiredArgsConstructor
public class PlatformBookingPurgeTxService {

    /** Statuses whose passenger data may ever be purged. Everything else (REVIEWING, HELD, BOOKING, UNKNOWN, CANCELLING) never is. */
    public static final Set<PlatformBookingStatus> TERMINAL = Set.of(
            PlatformBookingStatus.TICKETED, PlatformBookingStatus.CANCELLED, PlatformBookingStatus.FAILED, PlatformBookingStatus.ABORTED);

    static final String REMOVED = "[removed]";
    /** Event payload keys (lower case, no underscores) that can hold a passenger's details or supplier free text naming one. */
    private static final Set<String> PERSONAL_KEYS = Set.of("title", "firstname", "lastname", "name", "email", "phone", "mobile", "pan",
            "dob", "dateofbirth", "contact", "address", "message", "note", "reason", "error");
    /** A ticketed booking with no readable travel date is treated as travelling a year after ticketing (the longest an airline sells ahead). */
    private static final Duration UNDATED_TRAVEL_ALLOWANCE = Duration.ofDays(365);

    private final PlatformBookingRepository bookingRepository;
    private final PlatformBookingEventRepository eventRepository;
    private final PlatformBookingQueryService queryService;

    /** @return true when this call purged the booking */
    @Transactional
    public boolean purge(String bookingId, Instant now, int retentionDays) {
        PlatformBooking b = bookingRepository.findByIdForUpdate(bookingId).orElse(null);
        if (b == null || b.getPaxPurgedAt() != null || !TERMINAL.contains(b.getStatus())) {
            return false;
        }
        List<PlatformBookingEvent> events = eventRepository.findByPlatformBookingIdOrderByCreatedAtAsc(bookingId);
        if (now.isBefore(dueAt(b, events, retentionDays))) {
            return false;
        }
        b.setPaxEnc(null);
        b.setConfirmPayloadHash(null); // a hash of the passengers and contact: guessable, and useless once terminal
        b.setSupplierMessage(null);    // supplier free text, which can name a passenger
        b.setPaxPurgedAt(now);
        bookingRepository.save(b);
        for (PlatformBookingEvent e : events) {
            Map<String, Object> redacted = e.getPayload() == null ? null : redact(e.getPayload());
            if (!Objects.equals(redacted, e.getPayload())) {
                e.setPayload(redacted);
                eventRepository.save(e);
            }
        }
        return true;
    }

    /** TICKETED: travel date (hotel check-out, last flight departure) + retention. Others: last activity + retention. */
    private Instant dueAt(PlatformBooking b, List<PlatformBookingEvent> events, int retentionDays) {
        Duration retention = Duration.ofDays(retentionDays);
        if (b.getStatus() == PlatformBookingStatus.TICKETED) {
            LocalDate travel = travelDate(b);
            Instant travelled = travel != null ? travel.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
                    : (b.getTicketedAt() != null ? b.getTicketedAt() : b.getCreatedAt()).plus(UNDATED_TRAVEL_ALLOWANCE);
            return travelled.plus(retention);
        }
        Instant last = Stream.concat(Stream.of(b.getCancelledAt(), b.getResolvedFailedAt(), b.getCreatedAt()),
                        events.stream().map(PlatformBookingEvent::getCreatedAt))
                .filter(Objects::nonNull).max(Instant::compareTo).orElse(Instant.MAX.minus(retention));
        return last.plus(retention);
    }

    private LocalDate travelDate(PlatformBooking b) {
        if (b.getProduct() == PricingProduct.HOTEL) {
            var stay = queryService.hotel(b);
            return stay == null ? null : stay.checkOut();
        }
        return queryService.itinerary(b).stream().map(ItinerarySegment::getDeparture).filter(Objects::nonNull)
                .map(PlatformBookingPurgeTxService::dateOf).filter(Objects::nonNull).max(LocalDate::compareTo).orElse(null);
    }

    private static LocalDate dateOf(String isoDateTime) {
        try {
            return LocalDate.parse(isoDateTime.substring(0, Math.min(10, isoDateTime.length())));
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Object redactNode(Object node, String key) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), redactNode(v, String.valueOf(k))));
            return out;
        }
        if (node instanceof List<?> list) {
            return list.stream().map(v -> redactNode(v, key)).toList();
        }
        if (node != null && key != null && PERSONAL_KEYS.contains(key.toLowerCase(Locale.ROOT).replace("_", ""))) {
            return REMOVED;
        }
        return node;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> redact(Map<String, Object> payload) {
        return (Map<String, Object>) redactNode(payload, null);
    }
}
