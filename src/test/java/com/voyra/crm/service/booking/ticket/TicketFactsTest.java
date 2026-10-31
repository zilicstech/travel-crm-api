package com.voyra.crm.service.booking.ticket;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.exception.BookingConflictException;
import com.voyra.crm.models.booking.TicketLine;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketFactsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private static final Map<String, Object> MIX = Map.of("paxMix", Map.of("adults", 2, "children", 1, "infants", 0));

    private static List<String> keys(TicketFacts f) {
        return f.directions().stream().map(Direction::key).toList();
    }

    private static Map<String, Object> json(String s) throws Exception {
        return JSON.readValue(s, MAP);
    }

    private static Map<String, Object> fixtureItinerary() throws Exception {
        Map<String, Object> all = JSON.readValue(new File("src/test/resources/tripjack/flight-review-roundtrip.json"), MAP);
        return Map.of("tripInfos", all.get("tripInfos"));
    }

    @Test
    void readsTripjackRoundTripFixture() throws Exception {
        TicketFacts f = TicketFacts.from(fixtureItinerary(), MIX);
        assertThat(keys(f)).containsExactly("DEL-BOM", "BOM-DEL");
        assertThat(f.segments()).hasSize(2);
        Segment s = f.segments().get(0);
        assertThat(s.airlineCode()).isEqualTo("6E");
        assertThat(s.airlineName()).isEqualTo("IndiGo");
        assertThat(s.flightNumber()).isEqualTo("2031");
        assertThat(s.fromCode()).isEqualTo("DEL");
        assertThat(s.fromTerminal()).isEqualTo("1");
        assertThat(s.fromAirport()).isNull();
        assertThat(s.departs()).isEqualTo("2026-11-29T06:15");
        assertThat(s.durationMinutes()).isEqualTo(130);
        Direction back = f.directions().get(1);
        assertThat(back.segments()).hasSize(1);
        assertThat(back.fareType()).isEqualTo("PUBLISHED");
        assertThat(back.cabinClass()).isEqualTo("ECONOMY");
        assertThat(back.refundable()).isFalse();
        assertThat(back.checkInBaggage()).isEqualTo("15 Kg");
        assertThat(back.cabinBaggage()).isEqualTo("7 Kg");
        assertThat(f.namedTaxes()).isEmpty(); // the fixture has no afC.TAF component map
    }

    @Test
    void readsMockItinerary() throws Exception {
        Map<String, Object> it = json("""
                {"sI":[{"fD":{"fN":"101","aI":{"code":"MK","name":"Mock Air"}},"da":{"code":"DEL"},"aa":{"code":"BOM"},"duration":130},
                       {"fD":{"fN":"102"},"da":{"code":"BOM"},"aa":{"code":"DEL"},"isRs":true}],
                 "totalPriceList":[{"fareIdentifier":"PUBLISHED","fd":{"ADULT":{"cc":"ECONOMY","rT":1,"bI":{"iB":"15 Kg","cB":"7 Kg"}}}}]}""");
        TicketFacts f = TicketFacts.from(it, Map.of());
        assertThat(keys(f)).containsExactly("DEL-BOM", "BOM-DEL");
        assertThat(f.segments().get(1).durationMinutes()).isNull();
        assertThat(f.segments().get(1).airlineCode()).isNull();
        assertThat(f.directions().get(0).refundable()).isTrue();
        assertThat(f.directions().get(0).cabinClass()).isEqualTo("ECONOMY");
        assertThat(f.directions().get(1).segments()).hasSize(1);
        assertThat(f.namedTaxes()).isEmpty();
    }

    @Test
    void comboFareSplitsOnReturnMarkAndCountsItsTaxesOnce() throws Exception {
        Map<String, Object> it = json("""
                {"tripInfos":[{"sI":[{"da":{"code":"DEL"},"aa":{"code":"DXB"}},{"da":{"code":"DXB"},"aa":{"code":"DEL"},"isRs":true}],
                  "totalPriceList":[{"fd":{"ADULT":{"cc":"ECONOMY","bI":{"iB":"30 Kg"},"afC":{"TAF":{"YQ":10}}}}}]}]}""");
        TicketFacts f = TicketFacts.from(it, Map.of("paxMix", Map.of("adults", 1, "children", 0, "infants", 0)));
        assertThat(keys(f)).containsExactly("DEL-DXB", "DXB-DEL");
        assertThat(f.directions().get(1).checkInBaggage()).isEqualTo("30 Kg"); // both ways share the trip's fare
        assertThat(f.namedTaxes().get("YQ")).isEqualByComparingTo("10");
    }

    @Test
    void splitRoundTripKeepsEachDirectionsOwnFareFacts() throws Exception {
        Map<String, Object> it = json("""
                {"tripInfos":[
                 {"sI":[{"da":{"code":"DEL"},"aa":{"code":"BOM"}}],"totalPriceList":[{"fareIdentifier":"SAVER","fd":{"ADULT":{"cc":"ECONOMY","rT":0,"bI":{"iB":"15 Kg","cB":"7 Kg"}}}}]},
                 {"sI":[{"da":{"code":"BOM"},"aa":{"code":"DEL"}}],"totalPriceList":[{"fareIdentifier":"FLEX","fd":{"ADULT":{"cc":"BUSINESS","rT":1,"bI":{"iB":"25 Kg","cB":"10 Kg"}}}}]}]}""");
        TicketFacts f = TicketFacts.from(it, Map.of());
        Direction out = f.directions().get(0);
        Direction back = f.directions().get(1);
        assertThat(out.checkInBaggage()).isEqualTo("15 Kg");
        assertThat(back.checkInBaggage()).isEqualTo("25 Kg");
        assertThat(out.refundable()).isFalse();
        assertThat(back.refundable()).isTrue();
        assertThat(out.fareType()).isEqualTo("SAVER");
        assertThat(back.fareType()).isEqualTo("FLEX");
        assertThat(back.cabinClass()).isEqualTo("BUSINESS");
        assertThat(f.segments()).hasSize(2);
    }

    @Test
    void oneWayAndConnectingIsOneDirection() throws Exception {
        Map<String, Object> it = json("""
                {"sI":[{"da":{"code":"DEL"},"aa":{"code":"BOM"}},{"da":{"code":"BOM"},"aa":{"code":"GOI"}}]}""");
        TicketFacts f = TicketFacts.from(it, null);
        assertThat(keys(f)).containsExactly("DEL-GOI");
        Direction d = f.directions().get(0);
        assertThat(d.segments()).hasSize(2);
        assertThat(d.fareType()).isNull();
        assertThat(d.cabinClass()).isNull();
        assertThat(d.refundable()).isNull();
        assertThat(d.checkInBaggage()).isNull();
    }

    @Test
    void tripjackFareDetailVariants() throws Exception {
        // no ADULT entry: first type is used; numeric rT 0 and 2, text and unknown rT; a segment with no codes
        Map<String, Object> it = json("""
                {"tripInfos":[{"sI":[{"fD":{}}],"totalPriceList":[{"fd":{"CHILD":{"rT":2}}}]},{"sI":[]},{"sI":"x"}]}""");
        TicketFacts f = TicketFacts.from(it, Map.of());
        assertThat(keys(f)).containsExactly("---");
        assertThat(f.directions().get(0).refundable()).isTrue();
        assertThat(TicketFacts.from(json("{\"sI\":[{}],\"totalPriceList\":[{\"fd\":{\"ADULT\":{\"rT\":0}}}]}"), null).directions().get(0).refundable()).isFalse();
        assertThat(TicketFacts.from(json("{\"sI\":[{}],\"totalPriceList\":[{\"fd\":{\"ADULT\":{\"rT\":\"REFUNDABLE\"}}}]}"), null).directions().get(0).refundable()).isTrue();
        assertThat(TicketFacts.from(json("{\"sI\":[{}],\"totalPriceList\":[{\"fd\":{\"ADULT\":{\"rT\":\"?\"}}}]}"), null).directions().get(0).refundable()).isNull();
    }

    @Test
    void blankNullAndNestedValuesReadAsMissing() throws Exception {
        Segment s = TicketFacts.from(json("{\"sI\":[{\"fD\":{\"fN\":\" \",\"aI\":{\"code\":null,\"name\":{}}},\"da\":{\"code\":\"DEL\"},\"aa\":{\"code\":\"BOM\"}}]}"), null).segments().get(0);
        assertThat(s.flightNumber()).isNull();
        assertThat(s.airlineCode()).isNull();
        assertThat(s.airlineName()).isNull();
    }

    @Test
    void namedTaxesAreMultipliedByPaxMixAndSummedPerCode() throws Exception {
        Map<String, Object> it = json("""
                {"tripInfos":[{"sI":[{"da":{"code":"DEL"},"aa":{"code":"BOM"}}],"totalPriceList":[{"fd":{
                   "ADULT":{"afC":{"TAF":{"YQ":300.5,"K3":50}}},"CHILD":{"afC":{"TAF":{"YQ":100}}},"INFANT":{}}}]}]}""");
        Map<String, BigDecimal> t = TicketFacts.from(it, MIX).namedTaxes();
        assertThat(t).containsOnlyKeys("K3", "YQ");
        assertThat(t.get("YQ")).isEqualByComparingTo("701.00").hasScaleOf(2); // 300.5 * 2 + 100 * 1
        assertThat(t.get("K3")).isEqualByComparingTo("100.00");
    }

    @Test
    void unreadableBreakdownIsEmpty() throws Exception {
        String ok = "{\"sI\":[{}],\"totalPriceList\":[{\"fd\":{\"ADULT\":{\"afC\":{\"TAF\":{\"YQ\":1}}},\"CHILD\":{\"afC\":{\"TAF\":{\"YQ\":1}}}}}]}";
        assertThat(TicketFacts.from(json(ok), MIX).namedTaxes()).isNotEmpty();
        assertThat(TicketFacts.from(json(ok), null).namedTaxes()).isEmpty(); // no paxMix
        assertThat(TicketFacts.from(json(ok), Map.of("paxMix", Map.of("adults", 1, "children", 1))).namedTaxes()).isEmpty(); // infants missing
        // a counted pax type with no component map, a non-numeric amount
        String noChild = "{\"sI\":[{}],\"totalPriceList\":[{\"fd\":{\"ADULT\":{\"afC\":{\"TAF\":{\"YQ\":1}}}}}]}";
        assertThat(TicketFacts.from(json(noChild), MIX).namedTaxes()).isEmpty();
        String text = "{\"sI\":[{}],\"totalPriceList\":[{\"fd\":{\"ADULT\":{\"afC\":{\"TAF\":{\"YQ\":\"x\"}}}}}]}";
        String empty = "{\"sI\":[{}],\"totalPriceList\":[{\"fd\":{\"ADULT\":{\"afC\":{\"TAF\":{}}}}}]}";
        assertThat(TicketFacts.from(json(empty), Map.of("paxMix", Map.of("adults", 1, "children", 0, "infants", 0))).namedTaxes()).isEmpty();
        assertThat(TicketFacts.from(json(text), Map.of("paxMix", Map.of("adults", 1, "children", 0, "infants", 0))).namedTaxes()).isEmpty();
    }

    @Test
    void noSegmentsRefuses() throws Exception {
        for (Map<String, Object> it : new Map[]{null, Map.of(), json("{\"sI\":[]}"), json("{\"tripInfos\":[{\"sI\":[]}]}")}) {
            assertThatThrownBy(() -> TicketFacts.from(it, Map.of()))
                    .isInstanceOfSatisfying(BookingConflictException.class, e -> assertThat(e.getCode()).isEqualTo("TICKET_NOT_AVAILABLE"));
        }
    }

    @Test
    void ticketForPaxAndSector() {
        List<TicketLine> lines = List.of(
                new TicketLine(0, "DEL-BOM", "P1", "T0-out"), new TicketLine(0, "BOM-DEL", "P1", "T0-back"),
                new TicketLine(1, "DEL-BOM", "P1", "T1-out"), new TicketLine(1, "BOM-DEL", "P1", "T1-back"));
        assertThat(TicketFacts.ticketNumber(1, "BOM-DEL", lines, List.of(), 2)).isEqualTo("T1-back");
        assertThat(TicketFacts.ticketNumber(0, "BOM-DEL", lines, List.of(), 2)).isEqualTo("T0-back");
        assertThat(TicketFacts.ticketNumber(1, "DEL-BOM", lines, List.of(), 2)).isEqualTo("T1-out");
        assertThat(TicketFacts.ticketNumber(-1, "DEL-BOM", List.of(), List.of("A", "B"), 2)).isEqualTo("-");
        assertThat(TicketFacts.ticketNumber(0, "DEL-BOM", lines, List.of("X", "Y"), 2)).isEqualTo("T0-out");
        // a null-sector line matches any sector, but an exact one wins; a line without a number is skipped
        List<TicketLine> loose = List.of(new TicketLine(0, null, "P", "ANY"), new TicketLine(0, "DEL-BOM", "P", null),
                new TicketLine(0, "DEL-BOM", "P", "EXACT"), new TicketLine(0, null, "P", "LATER-ANY"), new TicketLine(1, "DEL-BOM", "P", "OTHER-PAX"));
        assertThat(TicketFacts.ticketNumber(0, "DEL-BOM", loose, List.of(), 2)).isEqualTo("EXACT");
        assertThat(TicketFacts.ticketNumber(0, "BOM-DEL", loose, List.of(), 2)).isEqualTo("ANY");
        // flat list: only one per passenger
        assertThat(TicketFacts.ticketNumber(1, "DEL-BOM", List.of(), List.of("A", "B"), 2)).isEqualTo("B");
        assertThat(TicketFacts.ticketNumber(1, "DEL-BOM", List.of(), List.of("A"), 2)).isEqualTo("-");
        assertThat(TicketFacts.ticketNumber(5, "DEL-BOM", List.of(), List.of("A", "B"), 2)).isEqualTo("-");
        assertThat(TicketFacts.ticketNumber(0, "DEL-BOM", List.of(), java.util.Arrays.asList(null, "B"), 2)).isEqualTo("-");
        assertThat(TicketFacts.ticketNumber(0, "DEL-BOM", List.of(), List.of(), 2)).isEqualTo("-");
    }
}
