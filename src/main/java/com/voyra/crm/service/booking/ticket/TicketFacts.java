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

/**
 * What the stored itinerary says about a flight, for the ticket. Reads the Tripjack shape ({@code tripInfos[]}, one entry
 * per direction, each with its own {@code sI} and {@code totalPriceList}) and the mock's top-level {@code sI}
 * (a round trip marks its return segments {@code isRs}). Fare type, cabin, refundability and baggage come from the first
 * direction's first fare detail (ADULT preferred); anything missing is null.
 */
public record TicketFacts(List<Segment> segments, String fareType, String cabinClass, Boolean refundable,
                          String checkInBaggage, String cabinBaggage, Map<String, BigDecimal> namedTaxes,
                          List<String> directions) {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> PAX_KEYS = Map.of("ADULT", "adults", "CHILD", "children", "INFANT", "infants");

    public static TicketFacts from(Map<String, Object> itinerary, Map<String, Object> pricingSnapshot) {
        JsonNode root = JSON.valueToTree(itinerary == null ? Map.of() : itinerary);
        List<JsonNode> dirs = new ArrayList<>(); // each: the segments of one direction
        List<JsonNode> fares = new ArrayList<>(); // each: the node holding that direction's totalPriceList
        if (root.path("tripInfos").isArray()) {
            for (JsonNode trip : root.path("tripInfos")) {
                dirs.add(trip.path("sI"));
                fares.add(trip);
            }
        } else {
            splitByReturnMark(root.path("sI"), dirs);
            fares.add(root);
        }
        List<Segment> segments = new ArrayList<>();
        List<String> directions = new ArrayList<>();
        for (JsonNode dir : dirs) {
            if (!dir.isArray() || dir.isEmpty()) {
                continue;
            }
            for (JsonNode s : dir) {
                segments.add(segment(s));
            }
            directions.add(code(dir.get(0).path("da")) + "-" + code(dir.get(dir.size() - 1).path("aa")));
        }
        if (segments.isEmpty()) {
            throw new BookingConflictException("TICKET_NOT_AVAILABLE", "This booking has no flight details, so a ticket cannot be made", Map.of());
        }
        JsonNode fd = fareDetail(fares.get(0));
        JsonNode first = fd.has("ADULT") ? fd.get("ADULT") : fd.elements().hasNext() ? fd.elements().next() : JSON.missingNode();
        return new TicketFacts(segments, text(fares.get(0).path("totalPriceList").path(0).path("fareIdentifier")),
                text(first.path("cc")), refundable(first.path("rT")), text(first.path("bI").path("iB")),
                text(first.path("bI").path("cB")), namedTaxes(fares, pricingSnapshot), directions);
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
        return flat.size() == paxCount && pax < paxCount && flat.get(pax) != null ? flat.get(pax) : "-";
    }

    private static void splitByReturnMark(JsonNode sI, List<JsonNode> dirs) {
        if (!sI.isArray()) {
            return;
        }
        var onward = JSON.createArrayNode();
        var back = JSON.createArrayNode();
        for (JsonNode s : sI) {
            (s.path("isRs").asBoolean(false) ? back : onward).add(s);
        }
        dirs.add(onward);
        dirs.add(back);
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

    private static JsonNode fareDetail(JsonNode holder) {
        return holder.path("totalPriceList").path(0).path("fd");
    }

    /** Per tax code: amount per passenger type times that type's count, summed over directions; empty when any part is unreadable. */
    private static Map<String, BigDecimal> namedTaxes(List<JsonNode> fares, Map<String, Object> pricingSnapshot) {
        JsonNode mix = JSON.valueToTree(pricingSnapshot == null ? Map.of() : pricingSnapshot).path("paxMix");
        Map<String, BigDecimal> out = new TreeMap<>();
        for (JsonNode holder : fares) {
            JsonNode fd = fareDetail(holder);
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
