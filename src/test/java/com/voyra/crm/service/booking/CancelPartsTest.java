package com.voyra.crm.service.booking;

import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.entity.PlatformCancelRequest;
import com.voyra.crm.enums.CancelRequestStatus;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.exception.BookingRequestException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CancelPartsTest {

    /** Tripjack shape: one trip with an onward and a return way (isRs), fares per type. */
    static Map<String, Object> roundTrip() {
        return Map.of("tripInfos", List.of(Map.of(
                "sI", List.of(
                        Map.of("da", Map.of("code", "DEL"), "aa", Map.of("code", "BOM"), "dt", "2026-12-01T09:00"),
                        Map.of("da", Map.of("code", "BOM"), "aa", Map.of("code", "DEL"), "dt", "2026-12-05T18:00", "isRs", true)),
                "totalPriceList", List.of(Map.of("fd", Map.of(
                        "ADULT", Map.of("fC", Map.of("TF", 3000)),
                        "INFANT", Map.of("fC", Map.of("TF", 1000)),
                        "CHILD", Map.of("fC", Map.of("TF", 0))))))));
    }

    private static PlatformBooking flight(Map<String, Object> itinerary) {
        return PlatformBooking.builder().id("PB1").product(PricingProduct.FLIGHT).itinerary(itinerary).build();
    }

    @Test
    void aFlightsJourneysAreItsSectorsKeyedByPosition() {
        assertThat(CancelParts.sectors(flight(roundTrip()))).containsExactly(
                new CancelParts.Sector("d0", "DEL-BOM", "2026-12-01"), new CancelParts.Sector("d1", "BOM-DEL", "2026-12-05"));
        // the mock's top-level sI, with a departure too short to be a date and one missing
        assertThat(CancelParts.sectors(flight(Map.of("sI", List.of(Map.of("da", Map.of("code", "GOI"), "aa", Map.of("code", "DEL"), "dt", "soon"))))))
                .containsExactly(new CancelParts.Sector("d0", "GOI-DEL", "soon"));
        assertThat(CancelParts.sectors(flight(Map.of("sI", List.of(Map.of("da", Map.of("code", "GOI")))))))
                .extracting(CancelParts.Sector::date).containsExactly((String) null);
    }

    @Test
    void aHotelOrAnUnreadableItineraryHasNoSectors() {
        assertThat(CancelParts.sectors(PlatformBooking.builder().product(PricingProduct.HOTEL).build())).isEmpty();
        assertThat(CancelParts.sectors(flight(null))).isEmpty();
    }

    @Test
    void onlyCompletedRequestsCountAsCancelledAndNoSectorsMeansAll() {
        PlatformCancelRequest done = PlatformCancelRequest.builder().status(CancelRequestStatus.COMPLETED).passengers(List.of(0))
                .sectors(List.of(Map.of("key", "d1", "label", "BOM-DEL"))).build();
        PlatformCancelRequest whole = PlatformCancelRequest.builder().status(CancelRequestStatus.COMPLETED).passengers(List.of(1)).sectors(List.of()).build();
        PlatformCancelRequest open = PlatformCancelRequest.builder().status(CancelRequestStatus.OPEN).passengers(List.of(2)).sectors(List.of()).build();

        Set<String> cancelled = CancelParts.cancelled(List.of(done, whole, open));

        assertThat(cancelled).containsExactly("0:d1", "1:*");
        assertThat(CancelParts.isCancelled(cancelled, 1, "d0")).isTrue();
        assertThat(CancelParts.isCancelled(cancelled, 0, "d0")).isFalse();
        assertThat(CancelParts.keys(null)).isEmpty();
    }

    @Test
    void wholeOnceEveryPassengerIsCancelledOnEverySector() {
        List<CancelParts.Sector> two = List.of(new CancelParts.Sector("d0", "A-B", null), new CancelParts.Sector("d1", "B-A", null));
        assertThat(CancelParts.whole(Set.of("0:*", "1:d0", "1:d1"), 2, two)).isTrue();
        assertThat(CancelParts.whole(Set.of("0:*", "1:d0"), 2, two)).isFalse();
        assertThat(CancelParts.whole(Set.of("0:*"), 2, List.of())).isFalse();
        assertThat(CancelParts.whole(Set.of("0:*", "1:*"), 2, List.of())).isTrue();
    }

    @Test
    void aChoiceMustNameKnownPartsNotYetCancelled() {
        List<CancelParts.Sector> sectors = CancelParts.sectors(flight(roundTrip()));
        List<String> types = List.of("ADULT", "ADULT");
        CancelParts.validate(List.of(0), List.of("d1"), types, sectors, Set.of());
        for (List<Integer> bad : List.of(List.<Integer>of(), java.util.Arrays.asList(0, null), List.of(5), List.of(-1), List.of(0, 0))) {
            assertThatThrownBy(() -> CancelParts.validate(bad, List.of(), types, sectors, Set.of())).isInstanceOf(BookingRequestException.class);
        }
        assertThatThrownBy(() -> CancelParts.validate(null, List.of(), types, sectors, Set.of())).isInstanceOf(BookingRequestException.class);
        assertThatThrownBy(() -> CancelParts.validate(List.of(0), List.of("d9"), types, sectors, Set.of())).isInstanceOf(BookingRequestException.class);
        assertThatThrownBy(() -> CancelParts.validate(List.of(0), List.of("d0", "d0"), types, sectors, Set.of())).isInstanceOf(BookingRequestException.class);
        assertThatThrownBy(() -> CancelParts.validate(List.of(0), List.of("d1"), types, sectors, Set.of("0:d1")))
                .hasMessage("Those passengers are already cancelled on those sectors");
        assertThatThrownBy(() -> CancelParts.validate(List.of(0), List.of(), types, sectors, Set.of("0:*")))
                .hasMessage("Those passengers are already cancelled on those sectors");
    }

    @Test
    void nobodyIsLeftAsAnUnaccompaniedChildOrInfant() {
        List<CancelParts.Sector> sectors = CancelParts.sectors(flight(roundTrip()));
        String message = "A child or infant can't be left travelling without an adult, and each infant needs an adult";
        assertThatThrownBy(() -> CancelParts.validate(List.of(0), List.of(), List.of("ADULT", "CHILD"), sectors, Set.of())).hasMessage(message);
        assertThatThrownBy(() -> CancelParts.validate(List.of(0), List.of("d0"), List.of("ADULT", "ADULT", "INFANT", "INFANT"), sectors, Set.of()))
                .hasMessage(message);
        // the adult and the infant together, or every adult and every minor, is fine; so is a hotel's whole stay
        CancelParts.validate(List.of(0, 1), List.of(), List.of("ADULT", "INFANT"), sectors, Set.of());
        CancelParts.validate(List.of(0, 1), List.of(), List.of("ADULT", "ADULT"), List.of(), Set.of());
    }

    @Test
    void theShareWeighsEachPassengerTypesFareAndTheSectorsChosen() {
        PlatformBooking b = flight(roundTrip());
        // adult 3000 + infant 1000 per sector: the infant alone on both sectors is a quarter
        assertThat(CancelParts.share(b, List.of(1), List.of(), List.of("ADULT", "INFANT"), 2)).isEqualByComparingTo("0.25");
        // the adult on one of two sectors: 3000 of 8000
        assertThat(CancelParts.share(b, List.of(0), List.of("d0"), List.of("ADULT", "INFANT"), 2)).isEqualByComparingTo("0.375");
        // a type with no usable fare (CHILD 0) weighs 1, like any type on an itinerary without fares
        assertThat(CancelParts.share(flight(Map.of()), List.of(0), List.of(), List.of("ADULT", "ADULT"), 0)).isEqualByComparingTo("0.5");
        assertThat(CancelParts.share(b, List.of(0), List.of(), List.of("CHILD", "CHILD"), 2)).isEqualByComparingTo("0.5");
        // a type whose fare is missing or not a number weighs 1 too
        PlatformBooking noFare = flight(Map.of("totalPriceList", List.of(Map.of("fd", Map.of("ADULT", Map.of("fC", Map.of("TF", "n/a")), "CHILD", Map.of("fC", Map.of()))))));
        assertThat(CancelParts.share(noFare, List.of(0), List.of(), List.of("ADULT", "CHILD"), 0)).isEqualByComparingTo("0.5");
        // nothing to weigh (no passengers on record) counts as the whole fare
        assertThat(CancelParts.share(flight(null), List.of(), List.of(), List.of(), 1)).isEqualByComparingTo(BigDecimal.ONE);
    }
}
