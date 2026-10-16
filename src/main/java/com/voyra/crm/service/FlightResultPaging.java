package com.voyra.crm.service;

import com.voyra.crm.dto.FlightGroupResponse;
import com.voyra.crm.dto.FlightOfferResponse;
import com.voyra.crm.dto.FlightSearchPageRequest;
import com.voyra.crm.dto.FlightSearchPageResponse;
import com.voyra.crm.enums.FlightSortOrder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure grouping / filtering / sorting / paging over a supplier result, kept free of Spring and
 * of the supplier so it is unit-testable. A supplier returns one row per fare; an agent wants one
 * row per flight with its fares underneath, so paging counts flights, never fares.
 */
final class FlightResultPaging {

    private static final Pattern HOURS = Pattern.compile("(\\d+)\\s*h", Pattern.CASE_INSENSITIVE);
    private static final Pattern MINUTES = Pattern.compile("(\\d+)\\s*m", Pattern.CASE_INSENSITIVE);

    private FlightResultPaging() {
    }

    /** One group per flight (airline, number, departure, arrival), fares sorted cheapest first. */
    static List<FlightGroupResponse> group(List<FlightOfferResponse> offers) {
        Map<String, FlightGroupResponse> byKey = new LinkedHashMap<>();
        for (FlightOfferResponse o : offers) {
            String key = o.getAirline() + "|" + o.getFlightNumber() + "|" + o.getDepartTime() + "|" + o.getArriveTime();
            FlightGroupResponse g = byKey.computeIfAbsent(key, k -> FlightGroupResponse.builder()
                    .key(k)
                    .airline(o.getAirline())
                    .airlineCode(o.getAirlineCode())
                    .flightNumber(o.getFlightNumber())
                    .origin(o.getOrigin())
                    .destination(o.getDestination())
                    .departTime(o.getDepartTime())
                    .arriveTime(o.getArriveTime())
                    .duration(o.getDuration())
                    .stops(o.getStops())
                    .fares(new ArrayList<>())
                    .build());
            g.getFares().add(o);
        }
        List<FlightGroupResponse> groups = new ArrayList<>(byKey.values());
        for (FlightGroupResponse g : groups) {
            g.getFares().sort(Comparator.comparing(FlightOfferResponse::getPrice));
            g.setCheapestPrice(g.getFares().get(0).getPrice());
        }
        return groups;
    }

    static FlightSearchPageResponse page(List<FlightGroupResponse> all, FlightSearchPageRequest req) {
        Set<String> airlines = new TreeSet<>();
        Map<String, String> airlineCodes = new LinkedHashMap<>();
        for (FlightGroupResponse g : all) {
            airlines.add(g.getAirline());
            if (g.getAirlineCode() != null && !g.getAirlineCode().isBlank()) {
                airlineCodes.putIfAbsent(g.getAirline(), g.getAirlineCode());
            }
        }

        List<FlightGroupResponse> filtered = new ArrayList<>();
        for (FlightGroupResponse g : all) {
            if (req.getMaxStops() != null && g.getStops() > req.getMaxStops()) {
                continue;
            }
            if (req.getAirlines() != null && !req.getAirlines().isEmpty() && !req.getAirlines().contains(g.getAirline())) {
                continue;
            }
            if (req.isRefundableOnly()) {
                List<FlightOfferResponse> kept = g.getFares().stream().filter(FlightOfferResponse::isRefundable).toList();
                if (kept.isEmpty()) {
                    continue;
                }
                // A copy: the cached group must keep every fare for the next, different filter.
                filtered.add(FlightGroupResponse.builder()
                        .key(g.getKey()).airline(g.getAirline()).airlineCode(g.getAirlineCode()).flightNumber(g.getFlightNumber())
                        .origin(g.getOrigin()).destination(g.getDestination())
                        .departTime(g.getDepartTime()).arriveTime(g.getArriveTime())
                        .duration(g.getDuration()).stops(g.getStops())
                        .cheapestPrice(kept.get(0).getPrice()).fares(kept).build());
                continue;
            }
            filtered.add(g);
        }

        filtered.sort(comparator(req.getSort()));

        String cheapestKey = filtered.stream().min(Comparator.comparing(FlightGroupResponse::getCheapestPrice))
                .map(FlightGroupResponse::getKey).orElse(null);
        String fastestKey = filtered.stream().min(Comparator.comparingInt(g -> durationMinutes(g.getDuration())))
                .map(FlightGroupResponse::getKey).orElse(null);

        int total = filtered.size();
        int pageSize = req.getPageSize();
        int totalPages = Math.max(1, (int) Math.ceil(total / (double) pageSize));
        int page = Math.min(Math.max(1, req.getPage()), totalPages);
        int from = Math.min((page - 1) * pageSize, total);
        int to = Math.min(from + pageSize, total);

        return FlightSearchPageResponse.builder()
                .flights(new ArrayList<>(filtered.subList(from, to)))
                .page(page)
                .pageSize(pageSize)
                .totalFlights(total)
                .totalPages(totalPages)
                .airlines(new ArrayList<>(airlines))
                .airlineCodes(airlineCodes)
                .cheapestKey(cheapestKey)
                .fastestKey(fastestKey)
                .build();
    }

    private static Comparator<FlightGroupResponse> comparator(FlightSortOrder sort) {
        Comparator<FlightGroupResponse> byPrice = Comparator.comparing(FlightGroupResponse::getCheapestPrice);
        return switch (sort) {
            case PRICE -> byPrice;
            case DURATION -> Comparator.<FlightGroupResponse>comparingInt(g -> durationMinutes(g.getDuration())).thenComparing(byPrice);
            case DEPARTURE -> Comparator.comparing(FlightGroupResponse::getDepartTime,
                    Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(byPrice);
        };
    }

    /** "140" (Tripjack, minutes), "3h 05m" (mock) or "2h" to minutes; unknown sorts last. */
    static int durationMinutes(String duration) {
        if (duration == null || duration.isBlank()) {
            return Integer.MAX_VALUE;
        }
        String d = duration.trim();
        if (d.matches("\\d+")) {
            return Integer.parseInt(d);
        }
        Matcher h = HOURS.matcher(d);
        Matcher m = MINUTES.matcher(d);
        int hours = h.find() ? Integer.parseInt(h.group(1)) : 0;
        int mins = m.find() ? Integer.parseInt(m.group(1)) : 0;
        return hours == 0 && mins == 0 ? Integer.MAX_VALUE : hours * 60 + mins;
    }

    static BigDecimal zeroIfNull(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
