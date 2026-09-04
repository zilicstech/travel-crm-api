package com.voyra.crm.service;

import com.voyra.crm.dto.FlightOfferResponse;
import com.voyra.crm.dto.FlightSearchQuery;
import com.voyra.crm.enums.FlightCabin;
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
@ConditionalOnProperty(name = "app.flight-supplier.provider", havingValue = "mock", matchIfMissing = true)
public class MockFlightSearchProvider implements FlightSearchProvider {

    private record Template(String airline, String flightNumberPrefix, LocalTime depart, int durationMinutes,
                             int stops, BigDecimal baseFare, boolean refundable, String fareType) {
    }

    private static final List<Template> TEMPLATES = List.of(
            new Template("IndiGo", "6E", LocalTime.of(6, 15), 185, 0, new BigDecimal("4200"), false, "Saver"),
            new Template("Air India", "AI", LocalTime.of(9, 30), 190, 0, new BigDecimal("5100"), true, "Flexi"),
            new Template("SpiceJet", "SG", LocalTime.of(14, 0), 240, 1, new BigDecimal("3800"), false, "Saver"),
            new Template("Vistara", "UK", LocalTime.of(18, 0), 180, 0, new BigDecimal("5600"), true, "Flexi")
    );

    private static final BigDecimal CABIN_MULTIPLIER_BUSINESS = new BigDecimal("3.2");
    private static final BigDecimal CABIN_MULTIPLIER_PREMIUM = new BigDecimal("1.6");
    private static final BigDecimal CABIN_MULTIPLIER_FIRST = new BigDecimal("5.0");

    @Override
    public List<FlightOfferResponse> search(FlightSearchQuery query) {
        int pax = Math.max(1, query.getAdults() + query.getChildren());
        BigDecimal cabinMultiplier = cabinMultiplier(query.getCabin());

        return TEMPLATES.stream()
                .map(t -> {
                    LocalTime arrive = t.depart().plusMinutes(t.durationMinutes());
                    BigDecimal price = t.baseFare().multiply(cabinMultiplier).multiply(BigDecimal.valueOf(pax));
                    return FlightOfferResponse.builder()
                            .offerId("mock-" + t.airline().toLowerCase().replace(" ", "") + "-" + t.depart())
                            .airline(t.airline())
                            .flightNumber(t.flightNumberPrefix() + "-" + (200 + t.depart().getHour()))
                            .origin(query.getOrigin())
                            .destination(query.getDestination())
                            .departTime(t.depart())
                            .arriveTime(arrive)
                            .duration(formatDuration(t.durationMinutes()))
                            .stops(t.stops())
                            .supplier("Tripjack")
                            .price(price.setScale(2, java.math.RoundingMode.HALF_UP))
                            .currency("INR")
                            .refundable(t.refundable())
                            .baggage(t.refundable() ? "20kg check-in + 7kg cabin" : "15kg check-in")
                            .fareType(t.fareType())
                            .build();
                })
                .toList();
    }

    private BigDecimal cabinMultiplier(FlightCabin cabin) {
        if (cabin == null) {
            return BigDecimal.ONE;
        }
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
