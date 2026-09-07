package com.voyra.crm.service;

import com.voyra.crm.dto.HotelOfferResponse;
import com.voyra.crm.dto.HotelSearchQuery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Dev/testing implementation returning plausible generated offers derived from the query,
 * so the search modal and the proposal flow can be exercised end to end with no vendor
 * account. Deterministic per query (no randomness) so a demo or a test can assert on it.
 * Mirrors {@link MockFlightSearchProvider}.
 */
@Service
@ConditionalOnProperty(name = "app.hotel-supplier.provider", havingValue = "mock", matchIfMissing = true)
public class MockHotelSearchProvider implements HotelSearchProvider {

    private record Template(String hotelName, int starRating, String roomType, String boardBasis,
                             BigDecimal baseRatePerNight, boolean refundable) {
    }

    private static final List<Template> TEMPLATES = List.of(
            new Template("Amari Beach Resort", 4, "Deluxe Room", "Breakfast Included",
                    new BigDecimal("6500"), true),
            new Template("Ibis Budget", 3, "Standard Room", "Room Only",
                    new BigDecimal("3200"), false),
            new Template("Grand Palace Hotel", 5, "Executive Suite", "Half Board",
                    new BigDecimal("11500"), true),
            new Template("Sunset Inn", 3, "Superior Room", "Breakfast Included",
                    new BigDecimal("4100"), false)
    );

    @Override
    public List<HotelOfferResponse> search(HotelSearchQuery query) {
        int nights = (int) Math.max(1, ChronoUnit.DAYS.between(query.getCheckIn(), query.getCheckOut()));
        int rooms = Math.max(1, query.getRooms());

        return TEMPLATES.stream()
                .map(t -> {
                    BigDecimal price = t.baseRatePerNight()
                            .multiply(BigDecimal.valueOf(nights))
                            .multiply(BigDecimal.valueOf(rooms));
                    return HotelOfferResponse.builder()
                            .offerId("mock-" + t.hotelName().toLowerCase().replace(" ", "") + "-" + query.getCheckIn())
                            .hotelName(t.hotelName())
                            .starRating(t.starRating())
                            .city(query.getCity())
                            .address(t.hotelName() + ", " + query.getCity())
                            .roomType(t.roomType())
                            .boardBasis(t.boardBasis())
                            .nights(nights)
                            .supplier("Tripjack")
                            .price(price.setScale(2, RoundingMode.HALF_UP))
                            .currency("INR")
                            .refundable(t.refundable())
                            .cancellationPolicy(t.refundable()
                                    ? "Free cancellation until 48 hours before check-in"
                                    : "Non-refundable")
                            .build();
                })
                .toList();
    }
}
