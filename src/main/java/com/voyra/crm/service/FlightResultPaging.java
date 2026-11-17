package com.voyra.crm.service;

import com.voyra.crm.dto.FlightGroupResponse;
import com.voyra.crm.dto.FlightOfferResponse;
import com.voyra.crm.dto.FlightSearchPageRequest;
import com.voyra.crm.dto.FlightSearchPageResponse;
import com.voyra.crm.dto.SearchSupplierResponse;
import com.voyra.crm.dto.FlightLegResponse;
import com.voyra.crm.enums.BaggageFilter;
import com.voyra.crm.enums.FlightDirection;
import com.voyra.crm.enums.FlightSearchTripType;
import com.voyra.crm.enums.FlightSortOrder;
import com.voyra.crm.enums.TimeBucket;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure grouping / filtering / sorting / paging over a supplier result, kept free of Spring and
 * of the supplier so it is unit-testable. A supplier returns one row per fare; an agent wants one
 * row per flight with its fares underneath, so paging counts flights, never fares.
 */
public final class FlightResultPaging {

    private static final Pattern HOURS = Pattern.compile("(\\d+)\\s*h", Pattern.CASE_INSENSITIVE);
    private static final Pattern MINUTES = Pattern.compile("(\\d+)\\s*m", Pattern.CASE_INSENSITIVE);

    private FlightResultPaging() {
    }

    /**
     * Canonical flight identity, so every supplier's shape of one flight lands in one row: per leg
     * {@code code|number|departure|arrival} (number = digits after the airline code, no leading zeros), legs joined with "/",
     * then the direction.
     */
    static String groupKey(FlightOfferResponse o) {
        List<String> segments = new ArrayList<>();
        if (o.getLegs() == null || o.getLegs().isEmpty()) {
            segments.add(segment(o.getAirlineCode(), o.getAirline(), o.getFlightNumber(),
                    o.getDepartDate(), o.getDepartTime(), o.getArriveDate(), o.getArriveTime()));
        } else {
            for (FlightLegResponse l : o.getLegs()) {
                boolean noCode = isBlank(l.getAirlineCode()) && isBlank(l.getAirline());
                segments.add(segment(noCode ? o.getAirlineCode() : l.getAirlineCode(), noCode ? o.getAirline() : l.getAirline(),
                        l.getFlightNumber(), l.getDepartDate(), l.getDepartTime(), l.getArriveDate(), l.getArriveTime()));
            }
        }
        return String.join("/", segments) + "|" + direction(o);
    }

    private static String segment(String airlineCode, String airline, String flightNumber,
                                  LocalDate departDate, LocalTime departTime, LocalDate arriveDate, LocalTime arriveTime) {
        String code = upper(isBlank(airlineCode) ? airline : airlineCode);
        String number = upper(flightNumber);
        if (!code.isEmpty() && number.startsWith(code)) {
            number = number.substring(code.length());
        }
        number = number.replaceAll("\\D", "").replaceFirst("^0+(?=\\d)", "");
        return code + "|" + number + "|" + stamp(departDate, departTime) + "|" + stamp(arriveDate, arriveTime);
    }

    private static String stamp(LocalDate date, LocalTime time) {
        return (date == null ? "" : date.toString()) + "T" + (time == null ? "" : time.truncatedTo(ChronoUnit.MINUTES).toString());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String upper(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    /** One group per flight; fares are left as found, {@link #summarise} orders them and fills the summary. */
    static List<FlightGroupResponse> group(List<FlightOfferResponse> offers) {
        Map<String, FlightGroupResponse> byKey = new LinkedHashMap<>();
        for (FlightOfferResponse o : offers) {
            FlightGroupResponse g = byKey.computeIfAbsent(groupKey(o), k -> FlightGroupResponse.builder()
                    .key(k)
                    .direction(direction(o))
                    .legs(o.getLegs())
                    .airline(o.getAirline())
                    .airlineCode(o.getAirlineCode())
                    .flightNumber(o.getFlightNumber())
                    .origin(o.getOrigin())
                    .destination(o.getDestination())
                    .departTime(o.getDepartTime())
                    .arriveTime(o.getArriveTime())
                    .departDate(o.getDepartDate())
                    .arriveDate(o.getArriveDate())
                    .duration(o.getDuration())
                    .stops(o.getStops())
                    .fares(new ArrayList<>())
                    .build());
            g.getFares().add(o);
        }
        return new ArrayList<>(byKey.values());
    }

    private static FlightDirection direction(FlightOfferResponse o) {
        return o.getDirection() == null ? FlightDirection.ONWARD : o.getDirection();
    }

    private static BigDecimal shown(FlightOfferResponse f) {
        return f.getDisplayedFare() != null ? f.getDisplayedFare() : f.getTotalPrice() != null ? f.getTotalPrice() : f.getPrice();
    }

    /**
     * A copy of {@code g} over {@code fares}, fares ordered bookable-fares-of-a-supplier-taking-bookings first, then by displayed
     * fare, then supplier name; the leading fare sets cheapestPrice and cheapestSupplierName. Never mutates {@code g}.
     */
    static FlightGroupResponse summarise(FlightGroupResponse g, List<FlightOfferResponse> fares, Set<String> bookingSupplierIds) {
        List<FlightOfferResponse> sorted = new ArrayList<>(fares);
        sorted.sort(Comparator.<FlightOfferResponse, Boolean>comparing(f -> !(f.isBookable() && f.getSupplierId() != null
                        && bookingSupplierIds.contains(f.getSupplierId())))
                .thenComparing(FlightResultPaging::shown, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(FlightOfferResponse::getSupplierName, Comparator.nullsLast(Comparator.naturalOrder())));
        FlightOfferResponse lead = sorted.get(0);
        return FlightGroupResponse.builder()
                .key(g.getKey()).airline(g.getAirline()).airlineCode(g.getAirlineCode()).flightNumber(g.getFlightNumber())
                .origin(g.getOrigin()).destination(g.getDestination())
                .departTime(g.getDepartTime()).arriveTime(g.getArriveTime())
                .departDate(g.getDepartDate()).arriveDate(g.getArriveDate())
                .duration(g.getDuration()).stops(g.getStops()).direction(g.getDirection()).legs(g.getLegs())
                .cheapestPrice(shown(lead)).cheapestSupplierName(lead.getSupplierName())
                .supplierCount((int) sorted.stream().map(FlightOfferResponse::getSupplierId).distinct().count())
                .fares(sorted).build();
    }

    static FlightSearchPageResponse page(List<FlightGroupResponse> all, FlightSearchPageRequest req, Set<String> bookingSupplierIds) {
        Set<String> airlines = new TreeSet<>();
        Map<String, String> airlineCodes = new LinkedHashMap<>();
        Map<String, SearchSupplierResponse> suppliers = new LinkedHashMap<>();
        for (FlightGroupResponse g : all) {
            airlines.add(g.getAirline());
            if (g.getAirlineCode() != null && !g.getAirlineCode().isBlank()) {
                airlineCodes.putIfAbsent(g.getAirline(), g.getAirlineCode());
            }
            for (FlightOfferResponse f : g.getFares()) {
                suppliers.putIfAbsent(String.valueOf(f.getSupplierId()), SearchSupplierResponse.builder()
                        .supplierId(f.getSupplierId()).supplierName(f.getSupplierName())
                        .bookingEnabled(f.getSupplierId() != null && bookingSupplierIds.contains(f.getSupplierId())).build());
            }
        }

        List<FlightGroupResponse> main = new ArrayList<>();
        List<FlightGroupResponse> returns = new ArrayList<>();
        for (FlightGroupResponse g : all) {
            (g.getDirection() == FlightDirection.RETURN ? returns : main).add(g);
        }
        // Combined only when every supplier answered COMBO; a COMBO answer among split ones stays as a row of the main list.
        FlightSearchTripType tripType = req.getQuery() == null || req.getQuery().getReturnDate() == null ? FlightSearchTripType.ONE_WAY
                : !main.isEmpty() && returns.isEmpty() && main.stream().allMatch(g -> g.getDirection() == FlightDirection.COMBO)
                ? FlightSearchTripType.ROUND_TRIP_COMBO : FlightSearchTripType.ROUND_TRIP_SPLIT;
        List<FlightGroupResponse> filtered = filterAndSort(main, req, bookingSupplierIds, false);
        List<BigDecimal> prices = all.stream().flatMap(g -> g.getFares().stream()).map(FlightResultPaging::shown)
                .filter(Objects::nonNull).sorted().toList();

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
                .suppliers(suppliers.values().stream()
                        .sorted(Comparator.comparing(SearchSupplierResponse::getSupplierName, Comparator.nullsLast(Comparator.naturalOrder())))
                        .toList())
                .priceMin(prices.isEmpty() ? null : prices.get(0))
                .priceMax(prices.isEmpty() ? null : prices.get(prices.size() - 1))
                .fareTypes(all.stream().flatMap(g -> g.getFares().stream()).map(FlightOfferResponse::getFareType)
                        .filter(t -> !isBlank(t)).distinct().sorted().toList())
                .origins(main.stream().map(FlightGroupResponse::getOrigin).filter(c -> !isBlank(c)).distinct().sorted().toList())
                .destinations(main.stream().map(FlightGroupResponse::getDestination).filter(c -> !isBlank(c)).distinct().sorted().toList())
                .cheapestKey(cheapestKey)
                .fastestKey(fastestKey)
                .tripType(tripType)
                // shortcut: the return list is not paged (filters and sort apply); page it too if a busy route makes the payload heavy
                .returnFlights(tripType == FlightSearchTripType.ROUND_TRIP_SPLIT ? filterAndSort(returns, req, bookingSupplierIds, true) : List.of())
                .build();
    }

    /**
     * The filters and sort of the request over one list; every kept group is a fresh summarised copy, a cached group is never mutated.
     * Stops, airline, times, flight number and airports drop a flight; price, fare type, refund type, baggage and supplier drop a fare,
     * and a flight with no fare left goes too. {@code back}: the list is the way back, so the airport filters are read the other way round.
     */
    private static List<FlightGroupResponse> filterAndSort(List<FlightGroupResponse> groups, FlightSearchPageRequest req,
                                                           Set<String> bookingSupplierIds, boolean back) {
        String typedNumber = alnum(req.getFlightNumber());
        List<FlightGroupResponse> filtered = new ArrayList<>();
        for (FlightGroupResponse g : groups) {
            // 0, 1 and 2 are exact ("1 stop" must not also list non-stop flights); 3 means three or more.
            if (req.getStops() != null && (req.getStops() >= 3 ? g.getStops() < 3 : g.getStops() != req.getStops())) {
                continue;
            }
            if (!in(req.getAirlines(), g.getAirline()) && !in(req.getAirlines(), g.getAirlineCode())) {
                continue;
            }
            if (!inBucket(req.getDepartBuckets(), g.getDepartTime()) || !inBucket(req.getArriveBuckets(), g.getArriveTime())
                    || !in(req.getOrigins(), back ? g.getDestination() : g.getOrigin())
                    || !in(req.getDestinations(), back ? g.getOrigin() : g.getDestination())
                    || !hasNumber(g, typedNumber)) {
                continue;
            }
            List<FlightOfferResponse> kept = g.getFares().stream()
                    .filter(f -> !req.isRefundableOnly() || f.isRefundable())
                    .filter(f -> in(req.getSuppliers(), f.getSupplierId()))
                    .filter(f -> inPrice(req, shown(f)))
                    .filter(f -> off(req.getFareTypes()) || req.getFareTypes().stream().anyMatch(t -> t.equalsIgnoreCase(f.getFareType())))
                    .filter(f -> in(req.getRefundTypes(), f.getRefundType()))
                    .filter(f -> req.getBaggage() == null || hasCheckIn(f.getBaggage()) == (req.getBaggage() == BaggageFilter.CHECK_IN))
                    .toList();
            if (!kept.isEmpty()) {
                filtered.add(summarise(g, kept, bookingSupplierIds));
            }
        }

        filtered.sort(comparator(req.getSort()));
        return filtered;
    }

    private static boolean off(List<?> filter) {
        return filter == null || filter.isEmpty();
    }

    private static boolean in(List<?> filter, Object value) {
        return off(filter) || value != null && filter.contains(value);
    }

    private static boolean inBucket(List<TimeBucket> buckets, LocalTime time) {
        return off(buckets) || time != null && buckets.contains(TimeBucket.of(time));
    }

    /** Against the fare the agency is shown for the whole party; a fare with no amount fails any price filter. */
    private static boolean inPrice(FlightSearchPageRequest req, BigDecimal shown) {
        if (req.getMinPrice() == null && req.getMaxPrice() == null) {
            return true;
        }
        return shown != null && (req.getMinPrice() == null || shown.compareTo(req.getMinPrice()) >= 0)
                && (req.getMaxPrice() == null || shown.compareTo(req.getMaxPrice()) <= 0);
    }

    /** "671", "SG671" and "6E-204" all find their flight: letters and digits only, against each leg's number with and without its airline code. */
    private static boolean hasNumber(FlightGroupResponse g, String typed) {
        return typed.isEmpty() || numbered(g.getAirlineCode(), g.getFlightNumber(), typed)
                || g.getLegs() != null && g.getLegs().stream().anyMatch(l -> numbered(l.getAirlineCode(), l.getFlightNumber(), typed));
    }

    private static boolean numbered(String airlineCode, String flightNumber, String typed) {
        String number = alnum(flightNumber);
        return number.contains(typed) || (alnum(airlineCode) + number).contains(typed);
    }

    private static String alnum(String s) {
        return upper(s).replaceAll("[^A-Z0-9]", "");
    }

    /** A check-in allowance that is not blank, zero ("0 Kg") or hand-baggage-only wording. */
    static boolean hasCheckIn(String baggage) {
        String b = upper(baggage);
        return !b.isEmpty() && !b.matches("0\\D*") && !b.contains("HAND");
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
    public static int durationMinutes(String duration) {
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
