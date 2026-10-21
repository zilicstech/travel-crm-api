package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One hotel opened from a search: its photos, description, amenities, policies and every bookable "
        + "room option for the searched dates and guests. Supplier text is plain text - HTML is stripped.")
public class HotelDetailResponse {

    @Schema(description = "Supplier hotel id (Tripjack tjHotelId)", example = "10000000012345")
    private String hotelId;

    @Schema(example = "Pride Plaza Hotel Aerocity")
    private String name;

    @Schema(description = "0-5", example = "5")
    private int starRating;

    @Schema(example = "Aerocity, New Delhi, India")
    private String address;

    @Schema(example = "New Delhi")
    private String city;

    @Schema(example = "28.5562")
    private BigDecimal latitude;

    @Schema(example = "77.1000")
    private BigDecimal longitude;

    @Schema(description = "Every photo the supplier has for the hotel, its rooms' photos after its own, the main one first")
    private List<Image> images;

    @Schema(description = "Named description paragraphs, plain text (overview, rooms, dining, ...)")
    private List<Section> descriptions;

    @Schema(description = "Amenity names", example = "[\"Indoor Pool\", \"Fitness facilities\"]")
    private List<String> amenities;

    @Schema(example = "13:00")
    private String checkInFrom;

    @Schema(example = "12:30")
    private String checkOutTill;

    @Schema(description = "Policies and fees to show before booking, plain text - mandatory fees first")
    private List<Section> policies;

    @Schema(description = "Every bookable option, cheapest first")
    private List<RoomOption> options;

    @Schema(description = "Total nights for the stay", example = "3")
    private int nights;

    @Schema(example = "Tripjack")
    private String supplier;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Image {
        @Schema(description = "Largest size the supplier gives")
        private String url;
        @Schema(description = "A smaller size for thumbnails; the same url when there is only one")
        private String thumbUrl;
        @Schema(example = "Guestroom")
        private String caption;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Section {
        @Schema(example = "Rooms")
        private String title;
        @Schema(description = "Plain text")
        private String text;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Room {
        @Schema(example = "224829")
        private String id;
        @Schema(example = "Deluxe, 2 Twin")
        private String name;
        @Schema(description = "How many of the searched rooms are this room type", example = "1")
        private int count;
        @Schema(example = "1 King Bed")
        private String beds;
        @Schema(example = "269")
        private Integer areaSqft;
        @Schema(example = "Courtyard view")
        private String view;
        @Schema(description = "Most guests the room takes", example = "3")
        private Integer maxOccupancy;
        @Schema(description = "Plain text")
        private String description;
        @Schema(description = "Room amenity names")
        private List<String> amenities;
        @Schema(description = "This room type's own photos")
        private List<Image> images;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoomOption {
        @Schema(description = "Supplier option id")
        private String optionId;
        @Schema(description = "The room type of each searched room, the same type merged with a count")
        private List<Room> rooms;
        @Schema(example = "Breakfast")
        private String mealBasis;
        @Schema(example = "[\"Airport transfer\"]")
        private List<String> inclusions;
        @Schema(description = "Rate rules to show before booking, plain text")
        private String bookingNotes;
        @Schema(description = "Total for all rooms and nights, taxes included", example = "27806.62")
        private BigDecimal price;
        @Schema(example = "INR")
        private String currency;
        @Schema(example = "true")
        private boolean refundable;
        @Schema(example = "Free cancellation until 2026-05-18 23:59")
        private String cancellationPolicy;
    }
}
