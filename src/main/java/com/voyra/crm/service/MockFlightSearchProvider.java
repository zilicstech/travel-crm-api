package com.voyra.crm.service;

import com.voyra.crm.dto.FlightLegResponse;
import com.voyra.crm.dto.FlightOfferResponse;
import com.voyra.crm.dto.FlightSearchQuery;
import com.voyra.crm.enums.FlightCabin;
import com.voyra.crm.enums.FlightDirection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

/**
 * Dev/testing implementation returning plausible generated offers derived from the query,
 * so the search modal and the proposal flow can be exercised end to end with no vendor
 * account. Deterministic per query (no randomness) so a demo or a test can assert on it.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "app.flight-supplier.provider", havingValue = "mock", matchIfMissing = true)
public class MockFlightSearchProvider implements FlightSearchProvider {

    private record Template(String airline, String flightNumberPrefix, LocalTime depart, int durationMinutes,
                             int stops, BigDecimal baseFare, boolean refundable, String fareType) {
    }

    private static final List<Template> TEMPLATES = List.of(
            new Template("IndiGo", "6E", LocalTime.of(6, 15), 185, 0, new BigDecimal("4200"), false, "Saver"),
            new Template("Air India", "AI", LocalTime.of(9, 30), 190, 0, new BigDecimal("5100"), true, "Flexi"),
            new Template("SpiceJet", "SG", LocalTime.of(14, 0), 240, 1, new BigDecimal("3800"), false, "Saver"),
            new Template("Vistara", "UK", LocalTime.of(18, 0), 180, 0, new BigDecimal("5600"), true, "Flexi"),
            // A red-eye, so the overnight "+1 day" arrival shows up in demos.
            new Template("Emirates", "EK", LocalTime.of(22, 45), 210, 0, new BigDecimal("6900"), true, "Flexi")
    );

    private record FareTier(String name, BigDecimal multiplier, boolean refundable, String baggage) {
    }

    private static final List<FareTier> FARE_TIERS = List.of(
            new FareTier("PUBLISHED", BigDecimal.ONE, false, "15kg check-in"),
            new FareTier("FLEXI_PLUS", new BigDecimal("1.18"), true, "20kg check-in + 7kg cabin"),
            new FareTier("CORPORATE", new BigDecimal("1.25"), true, "20kg check-in"),
            new FareTier("SME", new BigDecimal("1.30"), true, "25kg check-in + 7kg cabin")
    );

    /** Like the mock booking provider: the commissionable base is 85 % of the total fare. */
    private static final BigDecimal BASE_SHARE = new BigDecimal("0.85");
    private static final BigDecimal CHILD_SHARE = new BigDecimal("0.75");
    private static final BigDecimal INFANT_SHARE = new BigDecimal("0.10");
    private static final BigDecimal CABIN_MULTIPLIER_BUSINESS = new BigDecimal("3.2");
    private static final BigDecimal CABIN_MULTIPLIER_PREMIUM = new BigDecimal("1.6");
    private static final BigDecimal CABIN_MULTIPLIER_FIRST = new BigDecimal("5.0");

    @Override
    public List<FlightOfferResponse> search(FlightSearchQuery query) {
        log.warn("Flight search served by MOCK provider ({} -> {}); Tripjack was NOT called", query.getOrigin(), query.getDestination());
        FlightCabin cabin = query.getCabin() != null ? query.getCabin() : FlightCabin.ECONOMY;

        // Like the real supplier: one row per fare, several fares per flight; a round trip adds the way back as RETURN (never COMBO).
        List<FlightOfferResponse> offers = new java.util.ArrayList<>();
        emit(offers, query, cabin, query.getOrigin(), query.getDestination(), query.getDepartDate(), FlightDirection.ONWARD);
        if (query.getReturnDate() != null) {
            emit(offers, query, cabin, query.getDestination(), query.getOrigin(), query.getReturnDate(), FlightDirection.RETURN);
        }
        return offers;
    }

    private void emit(List<FlightOfferResponse> offers, FlightSearchQuery query, FlightCabin cabin, String origin,
                      String destination, java.time.LocalDate departDate, FlightDirection direction) {
        for (Template t : TEMPLATES) {
            LocalTime arrive = t.depart().plusMinutes(t.durationMinutes());
            java.time.LocalDate arriveDate = departDate == null ? null
                    : departDate.atTime(t.depart()).plusMinutes(t.durationMinutes()).toLocalDate();
            String flightNumber = t.flightNumberPrefix() + "-" + (200 + t.depart().getHour() + (direction == FlightDirection.RETURN ? 1 : 0));
            FlightLegResponse leg = FlightLegResponse.builder()
                    .airline(t.airline()).airlineCode(t.flightNumberPrefix()).flightNumber(flightNumber)
                    .origin(origin).destination(destination)
                    .departDate(departDate).departTime(t.depart()).arriveDate(arriveDate).arriveTime(arrive)
                    .duration(formatDuration(t.durationMinutes())).stops(t.stops()).build();
            for (FareTier tier : FARE_TIERS) {
                // The price is a function of the offer id alone, so the mock booking provider's review prices it the same.
                String offerId = "mock-" + key(t) + "-" + t.depart() + "-" + tier.name() + "-" + cabin.name()
                        + (direction == FlightDirection.RETURN ? RETURN_SUFFIX : "");
                BigDecimal adult = adultFare(offerId);
                BigDecimal party = partyFare(adult, query.getAdults(), query.getChildren(), query.getInfants());
                offers.add(FlightOfferResponse.builder()
                        .offerId(offerId)
                        .direction(direction)
                        .legs(List.of(leg))
                        .airline(t.airline())
                        .airlineCode(t.flightNumberPrefix())
                        .flightNumber(flightNumber)
                        .origin(origin)
                        .destination(destination)
                        .departTime(t.depart())
                        .arriveTime(arrive)
                        .departDate(departDate)
                        .arriveDate(arriveDate)
                        .duration(formatDuration(t.durationMinutes()))
                        .stops(t.stops())
                        .supplier("Tripjack")
                        .price(adult)
                        .totalPrice(party)
                        .baseFare(party.multiply(BASE_SHARE).setScale(2, java.math.RoundingMode.HALF_UP))
                        .passengers(FlightSearchProvider.passengers(query))
                        .currency("INR")
                        .refundable(tier.refundable())
                        .baggage(tier.baggage())
                        .fareType(tier.name())
                        .build());
            }
        }
    }

    /** Ends a mock RETURN offer id; the way back costs the same as the way out. */
    public static final String RETURN_SUFFIX = "-R";

    /** The mock fare per adult of an offer from its id alone ({@code mock-<airline>-<HH:mm>-<tier>-<cabin>[-R]}); null for any other id. */
    public static BigDecimal adultFare(String offerId) {
        String[] parts = offerId == null ? new String[0] : offerId.split("-");
        String[] p = parts.length == 6 && "R".equals(parts[5]) ? java.util.Arrays.copyOf(parts, 5) : parts;
        if (p.length != 5 || !"mock".equals(p[0])) {
            return null;
        }
        Template t = TEMPLATES.stream().filter(x -> key(x).equals(p[1]) && x.depart().toString().equals(p[2])).findFirst().orElse(null);
        FareTier tier = FARE_TIERS.stream().filter(x -> x.name().equals(p[3])).findFirst().orElse(null);
        FlightCabin cabin = java.util.Arrays.stream(FlightCabin.values()).filter(c -> c.name().equals(p[4])).findFirst().orElse(null);
        if (t == null || tier == null || cabin == null) {
            return null;
        }
        return t.baseFare().multiply(cabinMultiplier(cabin)).multiply(tier.multiplier()).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /** Like Tripjack: the adult fare, child and infant fares off it, totalled for the party (at least one adult). */
    public static BigDecimal partyFare(BigDecimal adult, int adults, int children, int infants) {
        BigDecimal child = adult.multiply(CHILD_SHARE).setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal infant = adult.multiply(INFANT_SHARE).setScale(2, java.math.RoundingMode.HALF_UP);
        return adult.multiply(BigDecimal.valueOf(Math.max(1, adults)))
                .add(child.multiply(BigDecimal.valueOf(children))).add(infant.multiply(BigDecimal.valueOf(infants)));
    }

    private static String key(Template t) {
        return t.airline().toLowerCase().replace(" ", "");
    }

    private static BigDecimal cabinMultiplier(FlightCabin cabin) {
        return switch (cabin) {
            case BUSINESS -> CABIN_MULTIPLIER_BUSINESS;
            case PREMIUM_ECONOMY -> CABIN_MULTIPLIER_PREMIUM;
            case FIRST -> CABIN_MULTIPLIER_FIRST;
            case ECONOMY -> BigDecimal.ONE;
        };
    }

    private String formatDuration(int minutes) {
        return (minutes / 60) + "h " + String.format("%02d", minutes % 60) + "m";
    }
}
