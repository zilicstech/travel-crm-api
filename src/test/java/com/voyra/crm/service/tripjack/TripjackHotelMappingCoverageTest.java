package com.voyra.crm.service.tripjack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.voyra.crm.dto.HotelDetailResponse;
import com.voyra.crm.dto.HotelOfferResponse;
import com.voyra.crm.dto.HotelSearchQuery;
import com.voyra.crm.dto.HotelThumbnailResponse;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The static mapping helpers of the Tripjack hotel provider, fed made-up v3-shaped JSON. */
class TripjackHotelMappingCoverageTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode j(String json) {
        try {
            return M.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- listing card

    @Test
    void anOfferWithoutAnIdUsesAPlainAddressAndPicksTheCheapestOption() {
        HotelOfferResponse o = TripjackHotelSearchProvider.toOffer(j("""
                {"name":"Hotel P","address":"Plain Street","rating":"3.6","options":[
                  {"optionId":"X","pricing":{"totalPrice":500}},
                  {"optionId":"Y","pricing":{"totalPrice":400,"baseRate":350},"roomInfo":[{"name":"Twin"},{"name":"Twin"}]},
                  {"optionId":"Z","pricing":{"totalPrice":450}}]}"""),
                "Goa", 2, "corr-1");

        assertThat(o.getHotelId()).isNull();
        assertThat(o.getOfferId()).isEqualTo("Y");
        assertThat(o.getAddress()).isEqualTo("Plain Street");
        assertThat(o.getStarRating()).isEqualTo(4);
        assertThat(o.getRoomType()).isEqualTo("Twin");
        assertThat(o.getBaseFare()).isEqualByComparingTo("350.00");
        assertThat(o.getCurrency()).isEqualTo("INR");
        assertThat(o.getBoardBasis()).isEmpty();
        assertThat(o.isRefundable()).isFalse();
        assertThat(o.getCancellationPolicy()).isEqualTo("Non-refundable");
        assertThat(o.getCorrelationId()).isEqualTo("corr-1");
    }

    @Test
    void anObjectAddressFallsBackToItsFirstLine() {
        HotelOfferResponse o = TripjackHotelSearchProvider.toOffer(j("""
                {"hid":"77","address":{"line_1":"Line One"},"starRating":"x","star_rating":2,"options":[{"optionId":"A","pricing":{}}]}"""),
                "Goa", 1, "c");

        assertThat(o.getHotelId()).isEqualTo("77");
        assertThat(o.getAddress()).isEqualTo("Line One");
        assertThat(o.getStarRating()).isEqualTo(2);
        assertThat(o.getPrice()).isEqualByComparingTo("0.00");
    }

    @Test
    void theBaseRateIsReadOnlyFromPositiveAllowListedFieldsOrAnExplicitTax() {
        assertThat(TripjackHotelSearchProvider.baseRate(j("{\"baseFare\":\"120.5\"}"))).isEqualByComparingTo("120.50");
        assertThat(TripjackHotelSearchProvider.baseRate(j("{\"baseRate\":0,\"totalPrice\":100,\"taxes\":18}"))).isEqualByComparingTo("82.00");
        assertThat(TripjackHotelSearchProvider.baseRate(j("{\"baseRate\":\"abc\",\"totalPrice\":100,\"tax\":\"18\"}"))).isNull();
        assertThat(TripjackHotelSearchProvider.baseRate(j("{\"totalPrice\":100,\"totalTax\":-1}"))).isNull();
        assertThat(TripjackHotelSearchProvider.baseRate(j("{\"totalPrice\":100,\"totalTax\":100}"))).isNull();
        assertThat(TripjackHotelSearchProvider.baseRate(j("{\"totalTax\":10}"))).isNull();
        assertThat(TripjackHotelSearchProvider.baseRate(j("{}"))).isNull();
    }

    @Test
    void aListingImageComesFromImagesOrAnyOfTheNamesAUrlGoesBy() {
        assertThat(TripjackHotelSearchProvider.listingImage(j("""
                {"images":[{"links":{"350px":{"href":"https://img.example.test/a_b.jpg"}}}]}""")))
                .isEqualTo("https://img.example.test/a_b.jpg");
        assertThat(TripjackHotelSearchProvider.listingImage(j("{\"images\":[{\"links\":{}}]}"))).isNull();
        assertThat(TripjackHotelSearchProvider.listingImage(j("{\"images\":[],\"heroImage\":\"https://img.example.test/h.jpg\"}")))
                .isEqualTo("https://img.example.test/h.jpg");
        assertThat(TripjackHotelSearchProvider.listingImage(j("{\"image\":{\"links\":{\"XL\":{\"href\":\"https://img.example.test/l.jpg\"}}}}")))
                .isEqualTo("https://img.example.test/l.jpg");
        assertThat(TripjackHotelSearchProvider.listingImage(j("{\"img\":{\"href\":\"https://img.example.test/r.jpg\"}}")))
                .isEqualTo("https://img.example.test/r.jpg");
        assertThat(TripjackHotelSearchProvider.listingImage(j("{\"thumbnail\":{\"url\":\"https://img.example.test/u.jpg\"}}")))
                .isEqualTo("https://img.example.test/u.jpg");
        assertThat(TripjackHotelSearchProvider.listingImage(j("{\"imageUrl\":\"/relative.jpg\"}"))).isNull();
        assertThat(TripjackHotelSearchProvider.listingImage(j("{}"))).isNull();
    }

    @Test
    void theCancellationPolicyEndsWhereTheLastFreeSlabEnds() {
        assertThat(TripjackHotelSearchProvider.cancellationPolicy(j("""
                {"deadlineDateTime":"2026-11-01T10:00:00","penalties":[
                  {"amount":0,"to":"2026-11-20T23:59:00"},{"amount":0,"to":""},{"amount":500,"to":"2026-11-30T00:00"},{"to":"2026-12-01"}]}"""),
                true)).isEqualTo("Free cancellation until 2026-11-20 23:59");
        assertThat(TripjackHotelSearchProvider.cancellationPolicy(j("{}"), true)).isEqualTo("Refundable");
    }

    @Test
    void roomsWithoutChildrenAgesOrRoomsUseTheDefaults() {
        HotelSearchQuery q = new HotelSearchQuery();
        q.setRooms(0);
        q.setAdults(0);
        q.setChildren(2);
        q.setChildAges(Arrays.asList(4, null));
        HotelSearchQuery noAges = new HotelSearchQuery();
        noAges.setRooms(1);
        noAges.setAdults(1);
        noAges.setChildren(1);
        noAges.setChildAges(null);

        assertThat(TripjackHotelSearchProvider.rooms(q)).containsExactly(
                Map.of("adults", 1, "children", 2, "childAge", List.of(4, 8)));
        assertThat(TripjackHotelSearchProvider.rooms(noAges).get(0)).containsEntry("childAge", List.of(8));
    }

    // ---------------------------------------------------------------- hotel page

    @Test
    void aHotelPageWithoutStaticDetailStillListsTheOptionsByPrice() {
        HotelDetailResponse d = TripjackHotelSearchProvider.toDetail("H1", j("""
                {"hotelName":" ","options":[
                  {"optionId":"B","pricing":{"totalPrice":300},"roomInfo":[{"id":"r1","name":"Double"},{"id":"r1","name":"Double"}],
                   "inclusions":["Wifi"," "],"bookingNotes":"<p>Note &amp; more</p>"},
                  {"optionId":"A","pricing":{"totalPrice":200,"currency":"USD"},"cancellation":{"isRefundable":true}}]}"""),
                null, "Goa", 2);

        assertThat(d.getName()).isEmpty();
        assertThat(d.getOptions()).extracting(HotelDetailResponse.RoomOption::getOptionId).containsExactly("A", "B");
        HotelDetailResponse.RoomOption b = d.getOptions().get(1);
        assertThat(b.getRooms()).singleElement().satisfies(r -> {
            assertThat(r.getCount()).isEqualTo(2);
            assertThat(r.getBeds()).isNull();
            assertThat(r.getAmenities()).isEmpty();
        });
        assertThat(b.getInclusions()).containsExactly("Wifi");
        assertThat(b.getBookingNotes()).isEqualTo("Note & more");
        assertThat(d.getOptions().get(0).getCancellationPolicy()).isEqualTo("Refundable");
        assertThat(d.getLatitude()).isNull();
        assertThat(d.getLongitude()).isNull();
        assertThat(d.getCheckInFrom()).isNull();
        assertThat(d.getPolicies()).isEmpty();
        assertThat(d.getImages()).isEmpty();
        assertThat(d.getCity()).isEqualTo("Goa");
        assertThat(d.getNights()).isEqualTo(2);
    }

    @Test
    void theStaticDetailFillsNamePoliciesAmenitiesAndRoomFacts() {
        JsonNode info = j("""
                {"name":"Static Name","star_rating":4.4,
                 "locale":{"address":{"fulladdr":"Full Address"},"coordinates":{"lat":15.5,"long":73.8}},
                 "policies":{"checkInCheckOut":{"checkin_from":"14:00","checkout_till":"11:00"},
                             "mandatory_fees":"<p>City tax</p>","optional_fees":""},
                 "amenities":[{"name":"Pool"},{"name":" "}],
                 "images":[{"links":{"original":{"href":"https://img.example.test/1.jpg"}}}],
                 "rooms":[{"id":"r1","views":[{"name":"Sea"},{"name":"Garden"}],"amenities":[{"name":"Balcony"}],
                           "area":{"square_feet":"320.4"},"occupancy":{"max_allowed":{"total":"3"}},
                           "bed_config":{"description":"1 King"},"descriptions":{"overview":"<b>Nice</b>"},
                           "images":[{"links":{"original":{"href":"https://img.example.test/1.jpg"}}},
                                     {"links":{"original":{"href":"https://img.example.test/2.jpg"}}}]},
                          {"id":"r2","area":{"square_feet":{}},"occupancy":{}}]}""");

        HotelDetailResponse d = TripjackHotelSearchProvider.toDetail("H1", j("""
                {"options":[{"optionId":"A","pricing":{"totalPrice":100},"roomInfo":[{"id":"r1","name":"Sea Room"},{"id":"r2","name":"Plain"}]}]}"""),
                info, "Goa", 1);

        assertThat(d.getName()).isEqualTo("Static Name");
        assertThat(d.getStarRating()).isEqualTo(4);
        assertThat(d.getAddress()).isEqualTo("Full Address");
        assertThat(d.getLatitude()).isEqualByComparingTo("15.5");
        assertThat(d.getLongitude()).isEqualByComparingTo("73.8");
        assertThat(d.getCheckInFrom()).isEqualTo("14:00");
        assertThat(d.getCheckOutTill()).isEqualTo("11:00");
        assertThat(d.getPolicies()).extracting(HotelDetailResponse.Section::getTitle, HotelDetailResponse.Section::getText)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Fees paid at the hotel", "City tax"));
        assertThat(d.getAmenities()).containsExactly("Pool");
        assertThat(d.getImages()).extracting(HotelDetailResponse.Image::getUrl)
                .containsExactly("https://img.example.test/1.jpg", "https://img.example.test/2.jpg");
        HotelDetailResponse.Room sea = d.getOptions().get(0).getRooms().get(0);
        assertThat(sea.getView()).isEqualTo("Sea, Garden");
        assertThat(sea.getAreaSqft()).isEqualTo(320);
        assertThat(sea.getMaxOccupancy()).isEqualTo(3);
        assertThat(sea.getBeds()).isEqualTo("1 King");
        assertThat(sea.getDescription()).isEqualTo("Nice");
        assertThat(sea.getAmenities()).containsExactly("Balcony");
        HotelDetailResponse.Room plain = d.getOptions().get(0).getRooms().get(1);
        assertThat(plain.getView()).isNull();
        assertThat(plain.getAreaSqft()).isNull();
        assertThat(plain.getMaxOccupancy()).isNull();
    }

    @Test
    void numericRoomFactsAreReadToo() {
        JsonNode info = j("""
                {"rooms":[{"id":"r1","area":{"square_feet":250},"occupancy":{"max_allowed":{"total":2}}}]}""");

        HotelDetailResponse.Room r = TripjackHotelSearchProvider.toDetail("H1",
                j("{\"options\":[{\"optionId\":\"A\",\"pricing\":{\"totalPrice\":1},\"roomInfo\":[{\"id\":\"r1\",\"name\":\"N\"}]}]}"),
                info, "Goa", 1).getOptions().get(0).getRooms().get(0);

        assertThat(r.getAreaSqft()).isEqualTo(250);
        assertThat(r.getMaxOccupancy()).isEqualTo(2);
    }

    @Test
    void photosPutTheHeroFirstAndSkipRepeatsAndLinklessOnes() {
        List<HotelDetailResponse.Image> images = TripjackHotelSearchProvider.images(j("""
                [{"links":{"XXL":{"href":"https://img.example.test/a.jpg"}},"caption":"A"},
                 {"links":{"XXL":{"href":"https://img.example.test/a.jpg"}}},
                 {"links":{}},
                 {"links":{"XXL":{"href":"https://img.example.test/b.jpg"}},"is_hero_image":true},
                 {"links":{"XXL":{"href":"https://img.example.test/c.jpg"}},"hero_image":true}]"""));

        assertThat(images).extracting(HotelDetailResponse.Image::getUrl).containsExactly(
                "https://img.example.test/c.jpg", "https://img.example.test/b.jpg", "https://img.example.test/a.jpg");
        assertThat(images.get(2).getCaption()).isEqualTo("A");
    }

    @Test
    void aLinkOfAnUnlistedSizeIsStillUsedAndNoneGivesNull() {
        assertThat(TripjackHotelSearchProvider.link(j("{\"500px\":{\"href\":\" \"},\"640px\":{\"href\":\"https://img.example.test/x.jpg\"}}"), true))
                .isEqualTo("https://img.example.test/x.jpg");
        assertThat(TripjackHotelSearchProvider.link(j("{\"500px\":{}}"), false)).isNull();
    }

    @Test
    void descriptionsReadInOrderWithUnknownSectionsLastAndEmptyOnesDropped() {
        List<HotelDetailResponse.Section> s = TripjackHotelSearchProvider.descriptions(j("""
                {"pet_policy":"Pets allowed","headline":"Lovely stay","location":"","dining":"Lovely stay"}"""));

        assertThat(s).extracting(HotelDetailResponse.Section::getTitle).containsExactly("Overview", "Pet policy");
    }

    @Test
    void flatTextReadsObjectsAndJsonStringsAndLabelsShortParts() {
        String longLine = "L".repeat(90);

        assertThat(TripjackHotelSearchProvider.flatText(null)).isEmpty();
        assertThat(TripjackHotelSearchProvider.flatText(MissingNode.getInstance())).isEmpty();
        assertThat(TripjackHotelSearchProvider.flatText(NullNode.getInstance())).isEmpty();
        assertThat(TripjackHotelSearchProvider.flatText(j("{\"spoken_languages\":\"Hindi\",\"misc\":\"" + longLine + "\",\"e\":\"\","
                + "\"multi\":\"<p>a</p><p>b</p>\"}")))
                .isEqualTo("Languages spoken: Hindi\n" + longLine + "\na\nb");
        assertThat(TripjackHotelSearchProvider.flatText(j("\"{\\\"_\\\":\\\"Hi\\\"}\""))).isEqualTo(": Hi");
        assertThat(TripjackHotelSearchProvider.flatText(j("\"{not json\""))).isEqualTo("{not json");
        assertThat(TripjackHotelSearchProvider.text(null)).isEmpty();
        assertThat(TripjackHotelSearchProvider.text(" ")).isEmpty();
    }

    // ---------------------------------------------------------------- thumbnails and ids

    @Test
    void aThumbnailWithoutAFullAddressJoinsItsLineAndCity() {
        HotelThumbnailResponse t = TripjackHotelSearchProvider.toThumbnail("H1", j("""
                {"locale":{"address":{"line_1":"Line A","city":"Goa"}}}"""));
        HotelThumbnailResponse cityOnly = TripjackHotelSearchProvider.toThumbnail("H1", j("""
                {"locale":{"address":{"line_1":"","city":"Goa"}}}"""));

        assertThat(t.getAddress()).isEqualTo("Line A, Goa");
        assertThat(t.getImageUrl()).isNull();
        assertThat(cityOnly.getAddress()).isEqualTo("Goa");
    }

    @Test
    void smallKeepsOtherHostsAndNull() {
        assertThat(TripjackHotelSearchProvider.small(null)).isNull();
        assertThat(TripjackHotelSearchProvider.small("https://other.example.test/a_z.jpg")).isEqualTo("https://other.example.test/a_z.jpg");
    }

    @Test
    void aHotelsIdIsTriedUnderEachNameItGoesBy() {
        assertThat(TripjackHotelSearchProvider.hotelIdOf(j("{\"tjHotelId\":\" \",\"hotelId\":\"9\"}"))).isEqualTo("9");
        assertThat(TripjackHotelSearchProvider.hotelIdOf(j("{\"tjHotelID\":\"8\"}"))).isEqualTo("8");
        assertThat(TripjackHotelSearchProvider.hotelIdOf(j("{}"))).isEmpty();
    }
}
