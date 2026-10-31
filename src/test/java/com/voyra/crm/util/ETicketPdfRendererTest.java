package com.voyra.crm.util;

import com.voyra.crm.service.booking.ticket.Direction;
import com.voyra.crm.service.booking.ticket.FareLine;
import com.voyra.crm.service.booking.ticket.Segment;
import com.voyra.crm.service.booking.ticket.TicketFacts;
import com.voyra.crm.util.ETicketPdfRenderer.ETicketData;
import com.voyra.crm.util.ETicketPdfRenderer.PaxRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ETicketPdfRendererTest {

    private static final Segment SEG = new Segment("6E", "IndiGo", "6E-123", "DEL", "Delhi", "3", "Indira Gandhi",
            "BOM", "Mumbai", "2", "Chhatrapati Shivaji", "2026-11-05T06:15", "2026-11-05T08:25", 130);
    private static final Segment BARE = new Segment("MK", null, null, null, null, null, null, null, null, null, null, null, null, null);
    private static final Direction OUT = new Direction("DEL-BOM", List.of(SEG), "PUBLISHED", "ECONOMY", false, "15 KG", "7 KG");
    private static final Direction BACK = new Direction("BOM-DEL", List.of(BARE), null, null, null, null, null);

    private static ETicketData data(List<PaxRow> pax, byte[] logo, boolean cancelled, boolean test, TicketFacts facts) {
        return new ETicketData("Sky Travels", "a@sky.in", "999", "1 Main Rd", logo, "3f2b9c1e-7a4d-4e0b-9c2f-1d5e8a6b7c90", Instant.parse("2026-10-31T05:00:00Z"),
                "Confirmed", cancelled, test, facts, pax,
                List.of(new FareLine("Base fare", new BigDecimal("4000")), new FareLine("Taxes, fees and charges", new BigDecimal("700.5"))),
                new BigDecimal("4700.5"));
    }

    private static TicketFacts facts() {
        return new TicketFacts(List.of(SEG, BARE), Map.of(), List.of(OUT, BACK));
    }

    private static List<PaxRow> pax() {
        return List.of(
                new PaxRow("Mr A One", "ADULT", LocalDate.of(1990, 1, 2), "P1", "IN", LocalDate.of(2030, 1, 1), "DEL-BOM", "PNR1", "T-111"),
                new PaxRow("Ms B Two", null, null, null, null, null, "BOM-DEL", null, null));
    }

    @Test
    void printsAgencyAirlineFlightsPassengersAndFare() throws Exception {
        byte[] pdf = ETicketPdfRenderer.render(data(pax(), null, false, false, facts()));
        String t = PdfText.of(pdf);
        String f = PdfText.flat(t);
        assertThat(t).contains("SKY TRAVELS", "a@sky.in", "Booking: 3f2b9c1e-7a4d-4e0b-9c2f-1d5e8a6b7c90", "Confirmed", "IndiGo", "PNR1", "Mr A One");
        assertThat(f).contains("Published", PdfText.flat("Non-refundable"), "T-111", "15KG", "INR4000.00",
                "INR4700.50", "Totalfare", "2h10m", "05Nov2026,06:15", "DEL,DelhiT3");
        // the second passenger takes the baggage of its own direction (none known here) and the first PNR of its own
        assertThat(f).contains("DEL-BOM", "BOM-DEL");
        Path out = Path.of("target/eticket-sample.pdf");
        Files.createDirectories(out.getParent());
        Files.write(out, pdf);
    }

    @Test
    void neverPrintsMarkupOrServiceFee() {
        String t = PdfText.of(ETicketPdfRenderer.render(data(pax(), null, false, false, facts()))).toLowerCase();
        assertThat(t).doesNotContain("markup", "service fee").doesNotContain("voyra");
    }

    @Test
    void missingValuesPrintDash() {
        ETicketData d = new ETicketData(null, null, null, null, null, null, null, null, false, false,
                new TicketFacts(List.of(BARE), Map.of(), List.of()), List.of(new PaxRow(null, null, null, null, null, null, null, null, null)),
                null, null);
        String t = PdfText.of(ETicketPdfRenderer.render(d));
        assertThat(t).doesNotContain("null").contains("-");
        // no facts at all, and a segment-less direction, also render
        assertThat(PdfText.of(ETicketPdfRenderer.render(data(List.of(), null, false, false, null)))).doesNotContain("null");
        assertThat(PdfText.of(ETicketPdfRenderer.render(data(null, null, false, false,
                new TicketFacts(null, Map.of(), List.of(new Direction(null, null, null, null, null, null, null))))))).doesNotContain("null");
        assertThat(PdfText.of(ETicketPdfRenderer.render(data(List.of(), null, false, false,
                new TicketFacts(null, Map.of(), null))))).doesNotContain("null");
    }

    @Test
    void unreadableLogoPrintsNameOnly() {
        byte[] webp = "RIFF....WEBPVP8 ".getBytes();
        assertThat(PdfText.of(ETicketPdfRenderer.render(data(pax(), webp, false, false, facts())))).contains("SKY TRAVELS");
    }

    @Test
    void readableLogoIsPrinted() throws Exception {
        byte[] png = getClass().getResourceAsStream("/airlines/AI.png").readAllBytes();
        assertThat(PdfText.of(ETicketPdfRenderer.render(data(pax(), png, false, false, facts())))).contains("SKY TRAVELS");
    }

    @Test
    void purgedReplacesPassengerTable() {
        String t = PdfText.of(ETicketPdfRenderer.render(data(null, null, false, false, facts())));
        assertThat(PdfText.flat(t)).contains(PdfText.flat(ETicketPdfRenderer.PURGED_TEXT)).doesNotContain("T-111");
    }

    @Test
    void cancelledAndTestWatermarks() {
        assertThat(PdfText.flat(PdfText.of(ETicketPdfRenderer.render(data(pax(), null, true, false, facts())))))
                .contains(PdfText.flat("NOT VALID FOR TRAVEL"));
        assertThat(PdfText.flat(PdfText.of(ETicketPdfRenderer.render(data(pax(), null, false, true, facts())))))
                .contains(PdfText.flat("TEST BOOKING - NOT VALID FOR TRAVEL"));
        assertThat(PdfText.of(ETicketPdfRenderer.render(data(pax(), null, false, false, facts())))).doesNotContain("NOT VALID");
    }

    @Test
    void noVoyraName() {
        assertThat(PdfText.of(ETicketPdfRenderer.render(data(pax(), null, true, true, facts()))).toLowerCase()).doesNotContain("voyra");
    }

    @Test
    void timesAndFlightNumbers() {
        Segment raw = new Segment("AI", "Air India", "101", "A", null, null, null, "B", null, null, null,
                "2026-11-05T06:15:30", "soon", null);
        Segment none = new Segment(null, null, null, "A", null, null, null, "B", null, null, null, null, null, null);
        String t = PdfText.flat(PdfText.of(ETicketPdfRenderer.render(data(pax(), null, false, false,
                new TicketFacts(List.of(raw), Map.of(), List.of(new Direction("A-B", List.of(raw, none), null, null, null, null, null)))))));
        assertThat(t).contains("05Nov2026,06:15", "soon", "AI101").doesNotContain("6E6E-123");
    }

    /** Fare types read as words, a whole booking UUID stays on one line, and month names are English whatever the server locale. */
    @Test
    void customerFacingTextIsReadable() throws Exception {
        String uuid = "3f2b9c1e-7a4d-4e0b-9c2f-1d5e8a6b7c90";
        ETicketData d = data(pax(), null, false, false, new TicketFacts(List.of(SEG), Map.of(), List.of(
                new Direction("DEL-BOM", List.of(SEG), "PUBLISHED", null, false, null, null),
                new Direction("BOM-DEL", List.of(SEG), "SME", null, null, null, null),
                new Direction("A-B", List.of(SEG), "SPECIAL_RETURN", null, null, null, null))));
        String t = PdfText.of(ETicketPdfRenderer.render(new ETicketData(d.agencyName(), d.email(), d.phone(), d.address(), null, uuid,
                d.issuedAt(), d.statusText(), false, false, d.facts(), d.passengers(), d.fare(), d.total())));
        assertThat(t).contains("Booking: " + uuid, "Published", "SME").doesNotContain("PUBLISHED");
        assertThat(PdfText.flat(t)).contains("SpecialReturn");
        for (String f : List.of("ISSUED", "WHEN", "DAY")) {
            java.lang.reflect.Field field = ETicketPdfRenderer.class.getDeclaredField(f);
            field.setAccessible(true);
            assertThat(((java.time.format.DateTimeFormatter) field.get(null)).getLocale()).isEqualTo(java.util.Locale.ENGLISH);
        }
    }

    @Test
    void factsShapesAndOddInputs() {
        Segment oddCode = new Segment("!!", "X", "1", "A", null, null, null, "B", null, null, null, null, null, null);
        Segment noCode = new Segment(null, "Y", "2", "A", null, null, null, "B", null, null, null, null, null, null);
        Direction refundable = new Direction("A-B", List.of(oddCode, noCode), null, null, true, null, null);
        List<PaxRow> stray = List.of(new PaxRow(" ", null, null, null, null, null, "Q-R", null, null));
        assertThat(PdfText.of(ETicketPdfRenderer.render(data(stray, null, false, false,
                new TicketFacts(List.of(SEG), Map.of(), List.of(refundable)))))).contains("Refundable");
        assertThat(PdfText.of(ETicketPdfRenderer.render(data(stray, null, false, false,
                new TicketFacts(List.of(SEG), Map.of(), List.of()))))).contains("IndiGo");
        assertThat(PdfText.of(ETicketPdfRenderer.render(data(stray, null, false, false,
                new TicketFacts(List.of(), Map.of(), List.of()))))).doesNotContain("IndiGo");
    }
}
