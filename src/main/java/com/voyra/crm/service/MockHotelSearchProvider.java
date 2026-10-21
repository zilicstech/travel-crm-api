package com.voyra.crm.service;

import com.voyra.crm.dto.HotelDetailResponse;
import com.voyra.crm.dto.HotelOfferResponse;
import com.voyra.crm.dto.HotelSearchQuery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Dev/testing implementation returning plausible generated offers derived from the query,
 * so the search modal and the proposal flow can be exercised end to end with no vendor
 * account. Deterministic per query (no randomness) so a demo or a test can assert on it.
 * Mirrors {@link MockFlightSearchProvider}. Its photos are placeholder images the frontend
 * serves from {@code public/mock-hotels/}.
 */
@Service
@ConditionalOnProperty(name = "app.hotel-supplier.provider", havingValue = "mock", matchIfMissing = true)
public class MockHotelSearchProvider implements HotelSearchProvider {

    private record Template(String hotelName, int starRating, String roomType, String boardBasis,
                             BigDecimal baseRatePerNight, boolean refundable) {
        String id() {
            return "mock-" + hotelName.toLowerCase().replace(" ", "-");
        }
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

    private static final List<String> HOTEL_PHOTOS = List.of("Exterior", "Lobby", "Pool", "Restaurant", "Spa", "View");

    @Override
    public List<HotelOfferResponse> search(HotelSearchQuery query) {
        int nights = nights(query);
        int rooms = Math.max(1, query.getRooms());

        return TEMPLATES.stream()
                .map(t -> HotelOfferResponse.builder()
                        .offerId("mock-" + t.hotelName().toLowerCase().replace(" ", "") + "-" + query.getCheckIn())
                        .hotelId(t.id())
                        .correlationId("mock-search")
                        .hotelName(t.hotelName())
                        .starRating(t.starRating())
                        .city(query.getCity())
                        .address(t.hotelName() + ", " + query.getCity())
                        .roomType(t.roomType())
                        .boardBasis(t.boardBasis())
                        .nights(nights)
                        .supplier("Tripjack")
                        .price(price(t.baseRatePerNight(), nights, rooms))
                        .currency("INR")
                        .refundable(t.refundable())
                        .cancellationPolicy(t.refundable()
                                ? "Free cancellation until 48 hours before check-in"
                                : "Non-refundable")
                        .build())
                .toList();
    }

    /** Like the real supplier, the list's photos come after the list itself - one per hotel, from the template's place. */
    @Override
    public List<com.voyra.crm.dto.HotelThumbnailResponse> thumbnails(List<String> hotelIds) {
        return IntStream.range(0, TEMPLATES.size())
                .filter(i -> hotelIds.contains(TEMPLATES.get(i).id()))
                .mapToObj(i -> com.voyra.crm.dto.HotelThumbnailResponse.builder()
                        .hotelId(TEMPLATES.get(i).id())
                        .imageUrl(photo(i + 1))
                        .starRating(TEMPLATES.get(i).starRating())
                        .build())
                .toList();
    }

    /** The hotel's page: six photos, its rooms' photos after them, and three options - its search room and two upgrades. */
    @Override
    public HotelDetailResponse detail(String hotelId, HotelSearchQuery query) {
        Template t = TEMPLATES.stream().filter(x -> x.id().equals(hotelId)).findFirst()
                .orElseThrow(() -> new com.voyra.crm.exception.NotFoundException("This hotel is no longer in the search - search again"));
        int nights = nights(query);
        int rooms = Math.max(1, query.getRooms());
        List<HotelDetailResponse.RoomOption> options = List.of(
                option(t, "std", t.roomType(), t.boardBasis(), BigDecimal.ONE, t.refundable(), "1 Queen Bed", 280, nights, rooms),
                option(t, "up", "Premier " + t.roomType(), "Breakfast Included", new BigDecimal("1.35"), true, "1 King Bed", 360, nights, rooms),
                option(t, "suite", "Family Suite", "Half Board", new BigDecimal("1.8"), true, "1 King Bed, 2 Twin Beds", 540, nights, rooms));
        List<HotelDetailResponse.Image> images = new java.util.ArrayList<>(IntStream.rangeClosed(1, HOTEL_PHOTOS.size())
                .mapToObj(i -> image(photo(i), HOTEL_PHOTOS.get(i - 1))).toList());
        images.addAll(List.of(image(roomPhoto(1), "Guestroom"), image(roomPhoto(2), "Bathroom"), image(roomPhoto(3), "Suite living area")));
        return HotelDetailResponse.builder()
                .hotelId(hotelId)
                .name(t.hotelName())
                .starRating(t.starRating())
                .address(t.hotelName() + ", " + query.getCity())
                .city(query.getCity())
                .images(images)
                .descriptions(List.of(
                        HotelDetailResponse.Section.builder().title("Overview").text("With a stay at " + t.hotelName() + " in " + query.getCity()
                                + ", you'll be within a 15-minute walk of the beach and close to the old town.\n"
                                + "Take in the views from a garden and make use of amenities such as concierge services and a pool.\n"
                                + "Make yourself at home in one of the air-conditioned rooms with a minibar and a safe. Free Wi-Fi keeps you connected.").build(),
                        HotelDetailResponse.Section.builder().title("Dining").text("A restaurant serves breakfast and dinner; a poolside bar is open until late.").build(),
                        HotelDetailResponse.Section.builder().title("Languages spoken").text("Hindi, English").build()))
                .amenities(List.of("Free Wi-Fi", "Swimming pool", "Fitness centre", "Restaurant", "Airport shuttle", "24-hour front desk"))
                .checkInFrom("14:00")
                .checkOutTill("12:00")
                .policies(List.of(HotelDetailResponse.Section.builder().title("Know before you go")
                        .text("A government-issued photo ID is needed at check-in.").build()))
                .options(options)
                .nights(nights)
                .supplier("Tripjack")
                .build();
    }

    private static HotelDetailResponse.RoomOption option(Template t, String key, String room, String board, BigDecimal factor,
                                                         boolean refundable, String beds, int sqft, int nights, int rooms) {
        int photo = "std".equals(key) ? 1 : "up".equals(key) ? 2 : 3;
        return HotelDetailResponse.RoomOption.builder()
                .optionId("mock-" + t.id() + "-" + key)
                .rooms(List.of(HotelDetailResponse.Room.builder()
                        .id(t.id() + "-" + key).name(room).count(rooms).beds(beds).areaSqft(sqft).view("City view").maxOccupancy(3)
                        .description(room + " with " + beds.toLowerCase() + ", " + sqft + " sq ft.")
                        .amenities(List.of("Air conditioning", "Minibar", "Safe"))
                        .images(List.of(image(roomPhoto(photo), room)))
                        .build()))
                .mealBasis(board)
                .inclusions("suite".equals(key) ? List.of("Airport transfer") : List.of())
                .price(price(t.baseRatePerNight().multiply(factor), nights, rooms))
                .currency("INR")
                .refundable(refundable)
                .cancellationPolicy(refundable ? "Free cancellation until 48 hours before check-in" : "Non-refundable")
                .build();
    }

    private static HotelDetailResponse.Image image(String url, String caption) {
        return HotelDetailResponse.Image.builder().url(url).thumbUrl(url).caption(caption).build();
    }

    private static String photo(int n) {
        return "/mock-hotels/hotel-" + n + ".svg";
    }

    private static String roomPhoto(int n) {
        return "/mock-hotels/room-" + n + ".svg";
    }

    private static int nights(HotelSearchQuery query) {
        return (int) Math.max(1, ChronoUnit.DAYS.between(query.getCheckIn(), query.getCheckOut()));
    }

    private static BigDecimal price(BigDecimal perNight, int nights, int rooms) {
        return perNight.multiply(BigDecimal.valueOf(nights)).multiply(BigDecimal.valueOf(rooms)).setScale(2, RoundingMode.HALF_UP);
    }
}
