package com.voyra.crm.service.booking.ticket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.exception.BookingConflictException;
import com.voyra.crm.models.booking.TicketLine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * What the stored itinerary says about a flight, for the ticket. Reads the Tripjack shape ({@code tripInfos[]}, each with
 * its own {@code sI} and {@code totalPriceList}) and the mock's top-level {@code sI}. Within one trip, segments flagged
 * {@code isRs} are the way back (a COMBO fare holds both ways), so one trip gives one or two directions that share the
 * trip's fare detail (ADULT, else the first pax type). Anything missing is null.
 */
public record TicketFacts(List<Segment> segments, Map<String, BigDecimal> namedTaxes, List<Direction> directions) {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> PAX_KEYS = Map.of("ADULT", "adults", "CHILD", "children", "INFANT", "infants");

    public static TicketFacts from(Map<String, Object> itinerary, Map<String, Object> pricingSnapshot) {
        JsonNode root = JSON.valueToTree(itinerary == null ? Map.of() : itinerary);
        List<JsonNode> trips = new ArrayList<>(); // each holds an sI and its totalPriceList
        if (root.path("tripInfos").isArray()) {
            root.path("tripInfos").forEach(trips::add);
        } else {
            trips.add(root);
        }
        List<Segment> segments = new ArrayList<>();
        List<Direction> directions = new ArrayList<>();
        for (JsonNode trip : trips) {
            JsonNode fd = trip.path("totalPriceList").path(0).path("fd");
            JsonNode fare = fd.has("ADULT") ? fd.get("ADULT") : fd.elements().hasNext() ? fd.elements().next() : JSON.missingNode();
            for (JsonNode way : split(trip.path("sI"))) {
                List<Segment> own = new ArrayList<>();
                way.forEach(s -> own.add(segment(s)));
                segments.addAll(own);
                directions.add(new Direction(code(way.get(0).path("da")) + "-" + code(way.get(way.size() - 1).path("aa")), own,
                        text(trip.path("totalPriceList").path(0).path("fareIdentifier")), text(fare.path("cc")), refundable(fare.path("rT")),
                        text(fare.path("bI").path("iB")), text(fare.path("bI").path("cB"))));
            }
        }
        if (segments.isEmpty()) {
            throw new BookingConflictException("TICKET_NOT_AVAILABLE", "This booking has no flight details, so a ticket cannot be made", Map.of());
        }
        return new TicketFacts(segments, namedTaxes(trips, pricingSnapshot), directions);
    }

    /** The ticket number to print for one passenger and sector, or "-". Callers pass an empty {@code flat} when the trip has more than one direction. */
    public static String ticketNumber(int pax, String sector, List<TicketLine> lines, List<String> flat, int paxCount) {
        TicketLine any = null;
        for (TicketLine l : lines) {
            if (l.paxIndex() != pax || l.ticketNumber() == null) {
                continue;
            }
            if (l.sector() != null && l.sector().equals(sector)) {
                return l.ticketNumber();
            }
            if (l.sector() == null && any == null) {
                any = l;
            }
        }
        if (any != null) {
            return any.ticketNumber();
        }
        return flat.size() == paxCount && pax >= 0 && pax < paxCount && flat.get(pax) != null ? flat.get(pax) : "-";
    }

    /** The non-empty ways of one segment list: onward, then the segments flagged isRs. */
    private static List<JsonNode> split(JsonNode sI) {
        var onward = JSON.createArrayNode();
        var back = JSON.createArrayNode();
        if (sI.isArray()) {
            sI.forEach(s -> (s.path("isRs").asBoolean(false) ? back : onward).add(s));
        }
        return Stream.of(onward, back).filter(w -> !w.isEmpty()).map(w -> (JsonNode) w).toList();
    }

    private static Segment segment(JsonNode s) {
        JsonNode da = s.path("da");
        JsonNode aa = s.path("aa");
        JsonNode fD = s.path("fD");
        return new Segment(text(fD.path("aI").path("code")), text(fD.path("aI").path("name")), text(fD.path("fN")),
                text(da.path("code")), text(da.path("city")), text(da.path("terminal")), text(da.path("name")),
                text(aa.path("code")), text(aa.path("city")), text(aa.path("terminal")), text(aa.path("name")),
                text(s.path("dt")), text(s.path("at")), s.path("duration").isInt() ? s.path("duration").asInt() : null);
    }

    /** Per tax code: amount per passenger type times that type's count, summed over trips; empty when any part is unreadable. */
    private static Map<String, BigDecimal> namedTaxes(List<JsonNode> fares, Map<String, Object> pricingSnapshot) {
        JsonNode mix = JSON.valueToTree(pricingSnapshot == null ? Map.of() : pricingSnapshot).path("paxMix");
        Map<String, BigDecimal> out = new TreeMap<>();
        for (JsonNode holder : fares) {
            JsonNode fd = holder.path("totalPriceList").path(0).path("fd");
            for (var type : PAX_KEYS.entrySet()) {
                JsonNode count = mix.path(type.getValue());
                if (!count.isInt()) {
                    return Map.of();
                }
                if (count.asInt() == 0) {
                    continue;
                }
                JsonNode taf = fd.path(type.getKey()).path("afC").path("TAF");
                if (!taf.isObject() || taf.isEmpty()) {
                    return Map.of();
                }
                for (var tax : taf.properties()) {
                    if (!tax.getValue().isNumber()) {
                        return Map.of();
                    }
                    out.merge(tax.getKey(), tax.getValue().decimalValue().multiply(BigDecimal.valueOf(count.asInt())).setScale(2, RoundingMode.HALF_UP), BigDecimal::add);
                }
            }
        }
        return out;
    }

    /** Tripjack's rT is 0 non-refundable, 1 refundable, 2 partly; the fixture and some responses spell it out. */
    private static Boolean refundable(JsonNode rT) {
        if (rT.isNumber()) {
            return rT.asInt() != 0;
        }
        return switch (rT.asText("")) {
            case "REFUNDABLE" -> true;
            case "NON_REFUNDABLE" -> false;
            default -> null;
        };
    }

    private static String code(JsonNode airport) {
        String c = text(airport.path("code"));
        return c == null ? "-" : c;
    }

    private static String text(JsonNode n) {
        return n.isValueNode() && !n.isNull() && !n.asText().isBlank() ? n.asText() : null;
    }
}
