package com.voyra.crm.service.tripjack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.dto.HotelDetailResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The hotel page from Tripjack's pricing and static-detail responses, in the shapes of their v3 docs. */
class TripjackHotelDetailMappingTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void theHotelPageCarriesEveryPhotoAndEachOptionsRoomFacts() throws Exception {
        JsonNode pricing = M.readTree("""
                {"tjHotelId":"100","hotelName":"Pride Plaza","options":[
                  {"optionId":"B","roomInfo":[{"id":"R1","name":"Deluxe, 2 Twin"},{"id":"R1","name":"Deluxe, 2 Twin"}],
                   "mealBasis":"Room Only","inclusions":["Wi-Fi"],"bookingNotes":"<b>Must</b> show<br/>these rules",
                   "pricing":{"totalPrice":27806.62,"currency":"INR"},
                   "cancellation":{"isRefundable":true,"penalties":[{"from":"2026-02-10T19:07:00","to":"2026-05-18T23:59:59","amount":0},
                                                                    {"from":"2026-05-18T23:59:59","to":"2026-05-26T00:00:00","amount":27759.42}]}},
                  {"optionId":"A","roomInfo":[{"id":"R9","name":"Standard"}],"mealBasis":"Breakfast",
                   "pricing":{"totalPrice":20000,"currency":"INR"},"cancellation":{"isRefundable":false}}]}
                """);
        JsonNode stat = M.readTree("""
                {"name":"Pride Plaza Hotel","star_rating":"5",
                 "locale":{"address":{"fulladdr":"Aerocity, New Delhi","city":"New Delhi"},"coordinates":{"lat":28.55,"long":77.1}},
                 "policies":{"checkInCheckOut":{"checkin_from":"13:00","checkout_till":"12:30"},"mandatory_fees":"<p>Resort fee &amp; tax</p>"},
                 "amenities":{"9":{"id":"9","name":"Fitness facilities"},"2820":{"id":"2820","name":"Indoor Pool"}},
                 "images":[{"caption":"Lobby","links":{"350px":{"href":"https://img/lobby-350.jpg"},"1000px":{"href":"https://img/lobby-1000.jpg"}}},
                           {"caption":"Featured","is_hero_image":true,"links":{"original":{"href":"https://img/hero.jpg"}}}],
                 "descriptions":{"default":"A <strong>great</strong> stay","rooms":"Rooms have views"},
                 "rooms":{"0":{"id":"R1","name":"Deluxe Room","descriptions":{"overview":"<strong>2 Twin Beds</strong><br />269 sq ft"},
                   "amenities":{"130":{"id":"130","name":"Refrigerator"}},
                   "images":[{"hero_image":true,"caption":"Guestroom","links":{"70px":{"href":"https://img/r1-70.jpg"},"1000px":{"href":"https://img/r1.jpg"}}}],
                   "bed_config":{"description":"2 Twin Beds"},"area":{"square_feet":269},
                   "views":{"4146":{"id":"4146","name":"Courtyard view"}},"occupancy":{"max_allowed":{"total":3}}}}}
                """);

        HotelDetailResponse d = TripjackHotelSearchProvider.toDetail("100", pricing, stat, "Delhi", 2);

        assertThat(d.getName()).isEqualTo("Pride Plaza");
        assertThat(d.getStarRating()).isEqualTo(5);
        assertThat(d.getCity()).isEqualTo("Delhi"); // the searched city, not the hotel's locality
        // Every photo, the main one first, then the rooms' own; largest size, a mid size as the thumbnail.
        assertThat(d.getImages()).extracting(HotelDetailResponse.Image::getUrl)
                .containsExactly("https://img/hero.jpg", "https://img/lobby-1000.jpg", "https://img/r1.jpg");
        assertThat(d.getImages().get(1).getThumbUrl()).isEqualTo("https://img/lobby-350.jpg");
        assertThat(d.getAmenities()).containsExactlyInAnyOrder("Fitness facilities", "Indoor Pool");
        assertThat(d.getDescriptions()).extracting(HotelDetailResponse.Section::getText).contains("A great stay");
        assertThat(d.getPolicies().get(0).getText()).isEqualTo("Resort fee & tax");
        assertThat(d.getCheckInFrom()).isEqualTo("13:00");

        // Cheapest first; two rooms of one type merge with a count, joined to the room type's facts.
        assertThat(d.getOptions()).extracting(HotelDetailResponse.RoomOption::getOptionId).containsExactly("A", "B");
        HotelDetailResponse.RoomOption b = d.getOptions().get(1);
        assertThat(b.getRooms()).singleElement().satisfies(r -> {
            assertThat(r.getCount()).isEqualTo(2);
            assertThat(r.getBeds()).isEqualTo("2 Twin Beds");
            assertThat(r.getAreaSqft()).isEqualTo(269);
            assertThat(r.getView()).isEqualTo("Courtyard view");
            assertThat(r.getMaxOccupancy()).isEqualTo(3);
            assertThat(r.getImages()).extracting(HotelDetailResponse.Image::getUrl).containsExactly("https://img/r1.jpg");
            assertThat(r.getDescription()).isEqualTo("2 Twin Beds\n269 sq ft");
        });
        assertThat(b.getBookingNotes()).isEqualTo("Must show\nthese rules");
        assertThat(b.getCancellationPolicy()).isEqualTo("Free cancellation until 2026-05-18 23:59");
        assertThat(d.getOptions().get(0).getCancellationPolicy()).isEqualTo("Non-refundable");
    }

    @Test
    void withNoStaticDetailTheOptionsStillShow() throws Exception {
        JsonNode pricing = M.readTree("""
                {"hotelName":"X","options":[{"optionId":"A","roomInfo":[{"id":"R","name":"Std"}],"pricing":{"totalPrice":100}}]}
                """);
        HotelDetailResponse d = TripjackHotelSearchProvider.toDetail("1", pricing, null, "Goa", 1);
        assertThat(d.getImages()).isEmpty();
        assertThat(d.getOptions()).singleElement().satisfies(o -> assertThat(o.getRooms().get(0).getName()).isEqualTo("Std"));
        assertThat(d.getCity()).isEqualTo("Goa");
    }

    /** Live listings have come back without tjHotelId: the id is read from whichever name it has, never sent on blank. */
    @Test
    void aHotelsIdIsReadWhateverItIsCalled() throws Exception {
        assertThat(TripjackHotelSearchProvider.hotelIdOf(M.readTree("{\"tjHotelId\":\"100\"}"))).isEqualTo("100");
        assertThat(TripjackHotelSearchProvider.hotelIdOf(M.readTree("{\"hid\":200}"))).isEqualTo("200");
        assertThat(TripjackHotelSearchProvider.hotelIdOf(M.readTree("{\"id\":\"300\",\"name\":\"X\"}"))).isEqualTo("300");
        assertThat(TripjackHotelSearchProvider.hotelIdOf(M.readTree("{\"name\":\"X\"}"))).isEmpty();
    }

    /** Tripjack repeats its overview across sections: each paragraph shows once, under a plain title, in reading order. */
    @Test
    void descriptionsReadInOrderUnderPlainTitlesWithNoRepeats() throws Exception {
        JsonNode d = M.readTree("""
                {"default":"Take in the views from a garden.<br/>Make yourself at home in one of 3 rooms.",
                 "headline":"With a stay at Krishna Apartments you'll be near Palolem Beach.<br/>Take in the views from a garden.",
                 "spoken_languages":"Hindi, English",
                 "dining":"<p>A cafe serves breakfast.</p>"}
                """);
        var sections = TripjackHotelSearchProvider.descriptions(d);
        assertThat(sections).extracting(com.voyra.crm.dto.HotelDetailResponse.Section::getTitle)
                .containsExactly("Overview", "About the hotel", "Dining", "Languages spoken");
        assertThat(sections.get(0).getText()).isEqualTo("With a stay at Krishna Apartments you'll be near Palolem Beach.\nTake in the views from a garden.");
        assertThat(sections.get(1).getText()).isEqualTo("Make yourself at home in one of 3 rooms.");
        // A short labelled part of a JSON description keeps its label.
        assertThat(TripjackHotelSearchProvider.flatText(M.readTree("{\"spoken_languages\":\"Hindi, English\"}"))).isEqualTo("Languages spoken: Hindi, English");
    }

    /** A listing that does carry a hero image, stars or an address shows them; one that doesn't, doesn't. */
    @Test
    void theListShowsWhatTheListingItselfCarries() throws Exception {
        JsonNode hotel = M.readTree("""
                {"hid":"77","name":"Sea View","rating":"4","heroImage":"https://img/77.jpg","address":"Calangute",
                 "options":[{"optionId":"O1","roomInfo":[{"name":"Std"}],"mealBasis":"Room Only","pricing":{"totalPrice":5000}}]}
                """);
        var o = TripjackHotelSearchProvider.toOffer(hotel, "Goa", 2, "C1");
        assertThat(o.getHotelId()).isEqualTo("77");
        assertThat(o.getImageUrl()).isEqualTo("https://img/77.jpg");
        assertThat(o.getStarRating()).isEqualTo(4);
        assertThat(o.getAddress()).isEqualTo("Calangute");
    }

    /** Readiness: only the documented base names count; anything else (a net or room rate) leaves the option not bookable. */
    @Test
    void theBaseRateIsReadOnlyFromTheAllowListedFields() throws Exception {
        assertThat(TripjackHotelSearchProvider.baseRate(M.readTree("{\"totalPrice\":1180,\"baseRate\":1000}"))).isEqualByComparingTo("1000");
        assertThat(TripjackHotelSearchProvider.baseRate(M.readTree("{\"totalPrice\":1180,\"baseFare\":\"1000\"}"))).isEqualByComparingTo("1000");
        assertThat(TripjackHotelSearchProvider.baseRate(M.readTree("{\"totalPrice\":1180,\"totalTax\":180}"))).isEqualByComparingTo("1000");
        assertThat(TripjackHotelSearchProvider.baseRate(M.readTree("{\"totalPrice\":1180,\"netRate\":900}"))).isNull();
        assertThat(TripjackHotelSearchProvider.baseRate(M.readTree("{\"totalPrice\":1180,\"roomRate\":1000}"))).isNull();
        assertThat(TripjackHotelSearchProvider.baseRate(M.readTree("{\"totalPrice\":1180,\"tax\":\"180\"}"))).isNull(); // tax not a number
        assertThat(TripjackHotelSearchProvider.baseRate(M.readTree("{\"totalPrice\":1180}"))).isNull();
    }
}
