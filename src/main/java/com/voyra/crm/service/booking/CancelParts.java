package com.voyra.crm.service.booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.entity.PlatformCancelRequest;
import com.voyra.crm.enums.CancelRequestStatus;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.exception.BookingRequestException;
import com.voyra.crm.service.booking.ticket.Direction;
import com.voyra.crm.service.booking.ticket.TicketFacts;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What of a platform booking can be cancelled, and what already was. A flight is passengers (positions in the stored
 * passenger list) times sectors (its journeys, keyed {@code d0}, {@code d1} in itinerary order); a hotel is the whole stay.
 * A request with no sectors means every sector (a hotel, or a flight whose itinerary cannot be read). Pairs are
 * {@code "<passenger>:<sector key>"}, with {@code "*"} for every sector.
 */
final class CancelParts {

    static final String ALL = "*";
    private static final ObjectMapper JSON = new ObjectMapper();

    record Sector(String key, String label, String date) {
    }

    private CancelParts() {
    }

    /** The booking's journeys; none for a hotel or an itinerary that cannot be read. */
    static List<Sector> sectors(PlatformBooking b) {
        if (b.getProduct() != PricingProduct.FLIGHT) {
            return List.of();
        }
        List<Direction> directions;
        try {
            directions = TicketFacts.from(b.getItinerary(), b.getPricingSnapshot()).directions();
        } catch (RuntimeException e) {
            return List.of();
        }
        List<Sector> out = new ArrayList<>();
        for (int i = 0; i < directions.size(); i++) {
            String departs = directions.get(i).segments().get(0).departs();
            out.add(new Sector("d" + i, directions.get(i).key(), departs == null || departs.length() < 10 ? departs : departs.substring(0, 10)));
        }
        return out;
    }

    /** Every pair a completed request cancelled. */
    static Set<String> cancelled(List<PlatformCancelRequest> requests) {
        Set<String> out = new LinkedHashSet<>();
        requests.stream().filter(r -> r.getStatus() == CancelRequestStatus.COMPLETED).forEach(r -> out.addAll(pairs(r.getPassengers(), keys(r.getSectors()))));
        return out;
    }

    static List<String> keys(List<Map<String, String>> sectors) {
        return sectors == null ? List.of() : sectors.stream().map(s -> s.get("key")).toList();
    }

    static List<String> pairs(List<Integer> passengers, List<String> sectorKeys) {
        List<String> out = new ArrayList<>();
        for (Integer p : passengers) {
            if (sectorKeys.isEmpty()) {
                out.add(p + ":" + ALL);
            } else {
                sectorKeys.forEach(k -> out.add(p + ":" + k));
            }
        }
        return out;
    }

    /** Whether passenger {@code p} is cancelled on sector {@code key} (or on every sector). */
    static boolean isCancelled(Set<String> cancelled, int p, String key) {
        return cancelled.contains(p + ":" + ALL) || cancelled.contains(p + ":" + key);
    }

    /** True once every passenger is cancelled on every sector. */
    static boolean whole(Set<String> cancelled, int paxCount, List<Sector> sectors) {
        for (int p = 0; p < paxCount; p++) {
            if (cancelled.contains(p + ":" + ALL)) {
                continue;
            }
            if (sectors.isEmpty()) {
                return false;
            }
            for (Sector s : sectors) {
                if (!cancelled.contains(p + ":" + s.key())) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Checks a choice of passengers and sectors against the booking and what is already cancelled: at least one
     * passenger, known positions and sectors, something not yet cancelled, and nobody left travelling as an
     * unaccompanied child or infant, nor more infants than adults on a sector.
     */
    static void validate(List<Integer> passengers, List<String> sectorKeys, List<String> paxTypes, List<Sector> sectors, Set<String> cancelled) {
        if (passengers == null || passengers.isEmpty()) {
            throw new BookingRequestException("INVALID_SELECTION", "Choose at least one passenger to cancel");
        }
        Set<String> known = new HashSet<>(sectors.stream().map(Sector::key).toList());
        if (passengers.stream().anyMatch(p -> p == null || p < 0 || p >= paxTypes.size()) || !known.containsAll(sectorKeys)
                || new HashSet<>(passengers).size() != passengers.size() || new HashSet<>(sectorKeys).size() != sectorKeys.size()) {
            throw new BookingRequestException("INVALID_SELECTION", "Choose passengers and sectors of this booking");
        }
        List<String> chosen = pairs(passengers, sectorKeys);
        Set<String> after = new HashSet<>(cancelled);
        after.addAll(chosen);
        if (chosen.stream().allMatch(pair -> isCancelled(cancelled, Integer.parseInt(pair.split(":")[0]), pair.split(":")[1]))) {
            throw new BookingRequestException("INVALID_SELECTION", "Those passengers are already cancelled on those sectors");
        }
        List<String> sectorsToCheck = sectors.isEmpty() ? List.of(ALL) : sectors.stream().map(Sector::key).toList();
        for (String key : sectorsToCheck) {
            int adults = 0;
            int minors = 0;
            int infants = 0;
            for (int p = 0; p < paxTypes.size(); p++) {
                if (isCancelled(after, p, key)) {
                    continue;
                }
                switch (paxTypes.get(p)) {
                    case "INFANT" -> { infants++; minors++; }
                    case "CHILD" -> minors++;
                    default -> adults++;
                }
            }
            if ((adults == 0 && minors > 0) || infants > adults) {
                throw new BookingRequestException("INVALID_SELECTION",
                        "A child or infant can't be left travelling without an adult, and each infant needs an adult");
            }
        }
    }

    /**
     * The chosen pairs' share of the fare, by passenger type: each type weighs its own fare from the itinerary
     * ({@code fd.<TYPE>.fC.TF}), 1 when unreadable; sectors weigh the same.
     */
    // shortcut: sectors are weighted equally, not by their own fare; good enough for a prefill the admin may change
    static BigDecimal share(PlatformBooking b, List<Integer> passengers, List<String> sectorKeys, List<String> paxTypes, int sectorCount) {
        Map<String, BigDecimal> weights = typeWeights(b.getItinerary());
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal chosen = BigDecimal.ZERO;
        int sectorsTotal = Math.max(1, sectorCount);
        int sectorsChosen = sectorKeys.isEmpty() ? sectorsTotal : sectorKeys.size();
        for (int p = 0; p < paxTypes.size(); p++) {
            BigDecimal w = weights.getOrDefault(paxTypes.get(p), BigDecimal.ONE);
            total = total.add(w.multiply(BigDecimal.valueOf(sectorsTotal)));
            if (passengers.contains(p)) {
                chosen = chosen.add(w.multiply(BigDecimal.valueOf(sectorsChosen)));
            }
        }
        return total.signum() == 0 ? BigDecimal.ONE : chosen.divide(total, 6, RoundingMode.HALF_UP);
    }

    private static Map<String, BigDecimal> typeWeights(Map<String, Object> itinerary) {
        JsonNode root = JSON.valueToTree(itinerary == null ? Map.of() : itinerary);
        List<JsonNode> trips = new ArrayList<>();
        if (root.path("tripInfos").isArray()) {
            root.path("tripInfos").forEach(trips::add);
        } else {
            trips.add(root);
        }
        Map<String, BigDecimal> out = new HashMap<>();
        for (JsonNode trip : trips) {
            trip.path("totalPriceList").path(0).path("fd").fields().forEachRemaining(e -> {
                JsonNode tf = e.getValue().path("fC").path("TF");
                if (tf.isNumber() && tf.decimalValue().signum() > 0) {
                    out.merge(e.getKey(), tf.decimalValue(), BigDecimal::add);
                }
            });
        }
        return out;
    }
}
