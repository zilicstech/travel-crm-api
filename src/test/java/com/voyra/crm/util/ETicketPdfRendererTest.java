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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ETicketPdfRendererTest {

    private static final String UUID = "3f2b9c1e-7a4d-4e0b-9c2f-1d5e8a6b7c90";
    private static final Segment SEG = new Segment("6E", "IndiGo", "6E-123", "DEL", "Delhi", "3", "Indira Gandhi",
            "BOM", "Mumbai", "2", "Chhatrapati Shivaji", "2026-11-05T06:15", "2026-11-05T08:25", 130);
    private static final Segment BACK_SEG = new Segment("SG", "SpiceJet", "SG-8170", "BOM", "Mumbai", "1", null,
            "DEL", "Delhi", null, null, "2026-11-09T19:40", "2026-11-09T21:55", 135);
    private static final Segment BARE = new Segment("MK", null, null, null, null, null, null, null, null, null, null, null, null, null);
    private static final Direction OUT = new Direction("DEL-BOM", List.of(SEG), "PUBLISHED", "ECONOMY", false, "15 KG", "7 KG");
    private static final Direction BACK = new Direction("BOM-DEL", List.of(BARE), null, null, null, null, null);
    private static final List<FareLine> FARE = List.of(new FareLine("Base fare", new BigDecimal("4000")),
            new FareLine("Taxes, fees and charges", new BigDecimal("700.5")));

    private static ETicketData data(List<PaxRow> pax, byte[] logo, boolean cancelled, boolean test, TicketFacts facts) {
        return new ETicketData("Sky Travels", "a@sky.in", "999", "1 Main Rd", logo, UUID, Instant.parse("2026-10-31T05:00:00Z"),
                "Confirmed", cancelled, test, facts, pax, FARE, new BigDecimal("4700.5"));
    }

    private static TicketFacts facts() {
        return new TicketFacts(List.of(SEG, BARE), Map.of(), List.of(OUT, BACK));
    }

    private static List<PaxRow> pax() {
        return List.of(new PaxRow("Mr A One", "ADULT", "DEL-BOM", "PNR1", "T-111"),
                new PaxRow("Ms B Two", null, "BOM-DEL", null, null));
    }

    private static String text(ETicketData d) {
        return PdfText.of(ETicketPdfRenderer.render(d));
    }

    @Test
    void printsAgencyBandFlightsPassengersAndFare() {
        String t = text(data(pax(), null, false, false, facts()));
        String f = PdfText.flat(t);
        assertThat(t).contains("Sky Travels", "a@sky.in", "999", "1 Main Rd", "E-TICKET", UUID, "Confirmed", "IndiGo", "PNR1",
                "Mr A One", "Adult");
        assertThat(f).contains("BOOKINGREFERENCE", "Issued31Oct2026,10:30", "Published", "Economy", PdfText.flat("Non-refundable"),
                "DelhitoMumbai", "CHECK-INBAGGAGE15KG", "CABINBAGGAGE7KG", "T-111", "INR4,000.00", "INR700.50", "Totalfare", "INR4,700.50",
                "2h10m", "06:15", "DELDelhi", "Thu,05Nov2026", "IndiraGandhi,Terminal3", "08:25", "BOMMumbai");
        // two directions: each passenger row names its sector; the second has no PNR or ticket number yet
        assertThat(f).contains("DEL-BOM", "BOM-DEL", "SECTOR");
        assertThat(t).doesNotContain("ADULT", "ECONOMY", "PUBLISHED", "Email:");
    }

    @Test
    void neverPrintsMarkupServiceFeeOrVoyra() {
        String t = text(data(pax(), null, true, true, facts())).toLowerCase();
        assertThat(t).doesNotContain("markup", "service fee", "voyra");
    }

    /** Owner ruling 2026-11-01: no date of birth, passport, nationality or passport expiry on the ticket. */
    @Test
    void printsNoIdentityDocuments() {
        String t = text(data(pax(), null, false, false, facts())).toLowerCase();
        assertThat(t).doesNotContain("nationality", "date of birth", "expiry");
        // "passport" appears only in the fixed ID advice, never as a column
        assertThat(t.split("passport", -1)).hasSize(2);
        assertThat(PdfText.flat(t)).contains("passengertypesectorpnrticketnumber");
    }

    @Test
    void indianDigitGroupingAndTwoDecimals() {
        assertThat(ETicketPdfRenderer.money(new BigDecimal("3811.4"))).isEqualTo("INR 3,811.40");
        assertThat(ETicketPdfRenderer.money(new BigDecimal("381140"))).isEqualTo("INR 3,81,140.00");
        assertThat(ETicketPdfRenderer.money(new BigDecimal("12345678.905"))).isEqualTo("INR 1,23,45,678.91");
        assertThat(ETicketPdfRenderer.money(new BigDecimal("999"))).isEqualTo("INR 999.00");
        assertThat(ETicketPdfRenderer.money(new BigDecimal("-1500"))).isEqualTo("INR -1,500.00");
        assertThat(ETicketPdfRenderer.money(null)).isEqualTo("-");
    }

    @Test
    void emptyContactFieldsAreLeftOut() {
        ETicketData d = new ETicketData("Sky Travels", " ", null, null, null, UUID, null, "Confirmed", false, false,
                facts(), pax(), FARE, null);
        String t = text(d);
        assertThat(t).contains("Sky Travels", "Issued -").doesNotContain("Email", "Phone", "null");
        assertThat(PdfText.flat(t)).contains("Totalfare-");
    }

    @Test
    void missingValuesPrintDash() {
        ETicketData d = new ETicketData(null, null, null, null, null, null, null, null, false, false,
                new TicketFacts(List.of(BARE), Map.of(), List.of()), List.of(new PaxRow(null, null, null, null, null)),
                null, null);
        assertThat(text(d)).doesNotContain("null").contains("-");
        // no facts at all, and a segment-less direction, also render
        assertThat(text(data(List.of(), null, false, false, null))).doesNotContain("null");
        assertThat(text(data(null, null, false, false,
                new TicketFacts(null, Map.of(), List.of(new Direction(null, null, null, null, null, null, null)))))).doesNotContain("null");
        assertThat(text(data(List.of(), null, false, false, new TicketFacts(null, Map.of(), null)))).doesNotContain("null");
    }

    @Test
    void unreadableLogoPrintsNameOnly() {
        byte[] webp = "RIFF....WEBPVP8 ".getBytes();
        assertThat(text(data(pax(), webp, false, false, facts()))).contains("Sky Travels");
    }

    @Test
    void readableLogoIsPrinted() throws Exception {
        byte[] png = getClass().getResourceAsStream("/airlines/AI.png").readAllBytes();
        assertThat(text(data(pax(), png, false, false, facts()))).contains("Sky Travels");
    }

    @Test
    void purgedReplacesPassengerTable() {
        String t = text(data(null, null, false, false, facts()));
        assertThat(PdfText.flat(t)).contains(PdfText.flat(ETicketPdfRenderer.PURGED_TEXT)).doesNotContain("T-111", "Ticketnumber");
    }

    @Test
    void cancelledAndTestWatermarks() {
        assertThat(PdfText.flat(text(data(pax(), null, true, false, facts())))).contains(PdfText.flat("NOT VALID FOR TRAVEL"));
        assertThat(PdfText.flat(text(data(pax(), null, false, true, facts())))).contains(PdfText.flat("TEST BOOKING - NOT VALID FOR TRAVEL"));
        assertThat(text(data(pax(), null, false, false, facts()))).doesNotContain("NOT VALID");
    }

    @Test
    void timesAndFlightNumbers() {
        Segment raw = new Segment("AI", "Air India", "101", "A", null, null, null, "B", null, null, null,
                "2026-11-05T06:15:30", "soon", null);
        Segment none = new Segment(null, null, null, "A", null, null, null, "B", null, null, null, null, null, null);
        String t = PdfText.flat(text(data(pax(), null, false, false,
                new TicketFacts(List.of(raw), Map.of(), List.of(new Direction("A-B", List.of(raw, none), null, null, null, null, null))))));
        assertThat(t).contains("06:15", "soon", "AI101", "ChangeofplaneatA").doesNotContain("6E6E-123");
    }

    /** One PNR shared by both directions prints once; per-direction PNRs print one line each; month names are English. */
    @Test
    void pnrBandAndReadableFacts() throws Exception {
        List<PaxRow> same = List.of(new PaxRow("Mr A One", "ADULT", "DEL-BOM", "ONE1", "T1"),
                new PaxRow("Mr A One", "ADULT", "BOM-DEL", "ONE1", "T2"));
        TicketFacts two = new TicketFacts(List.of(SEG), Map.of(), List.of(
                new Direction("DEL-BOM", List.of(SEG), "SME", null, true, null, null),
                new Direction("BOM-DEL", List.of(BACK_SEG), "SPECIAL_RETURN", "PREMIUM_ECONOMY", null, null, null)));
        String t = text(data(same, null, false, false, two));
        assertThat(t.split("ONE1", -1)).hasSize(4); // the band once, then each row
        assertThat(t).contains("SME", "Refundable", "Premium Economy");
        assertThat(PdfText.flat(t)).contains("SpecialReturn");
        String split = PdfText.flat(text(data(pax(), null, false, false, facts())));
        assertThat(split).contains("DEL-BOMPNR1", "BOM-DEL-");
        for (String f : List.of("ISSUED", "TIME", "WEEKDAY")) {
            java.lang.reflect.Field field = ETicketPdfRenderer.class.getDeclaredField(f);
            field.setAccessible(true);
            assertThat(((java.time.format.DateTimeFormatter) field.get(null)).getLocale()).isEqualTo(java.util.Locale.ENGLISH);
        }
    }

    /** A connection prints the change of plane with the layover, and the stop count; unreadable times print no layover. */
    @Test
    void connectionsAndStops() {
        Segment second = new Segment("6E", "IndiGo", "6E-5", "BOM", "Mumbai", null, null, "GOI", "Goa", null, null,
                "2026-11-05T10:40", "2026-11-05T11:55", 75);
        String one = PdfText.flat(text(data(pax(), null, false, false,
                new TicketFacts(null, Map.of(), List.of(new Direction("DEL-GOI", List.of(SEG, second), null, null, null, null, null))))));
        assertThat(one).contains("1stop", "ChangeofplaneatMumbai(BOM)", "Layover2h15m", "DelhitoGoa");
        String two = PdfText.flat(text(data(pax(), null, false, false,
                new TicketFacts(null, Map.of(), List.of(new Direction("X", List.of(BARE, SEG, BARE), null, null, null, null, null))))));
        assertThat(two).contains("2stops", "Changeofplaneat-").doesNotContain("Layover");
        String nonStop = PdfText.flat(text(data(pax(), null, false, false, facts())));
        assertThat(nonStop).contains("Non-stop");
    }

    /** Rows of one person share their name cell; a sector seen again starts another person; one-way trips need no sector column. */
    @Test
    void passengerRowsAndColumns() {
        List<PaxRow> twins = List.of(new PaxRow("Mr X", "ADULT", "DEL-BOM", "P", "T1"), new PaxRow("Mr X", "ADULT", "DEL-BOM", "P", "T2"));
        String oneWay = PdfText.flat(text(data(twins, null, false, false, new TicketFacts(null, Map.of(), List.of(OUT)))));
        assertThat(oneWay).contains("MrXAdultPT1", "MrXAdultPT2").doesNotContain("SECTOR");
        List<PaxRow> stray = List.of(new PaxRow(" ", null, "Q-R", null, null));
        assertThat(PdfText.flat(text(data(stray, null, false, false, new TicketFacts(null, Map.of(), List.of(OUT)))))).contains("SECTOR", "Q-R");
    }

    @Test
    void factsShapesAndOddInputs() {
        Segment oddCode = new Segment("!!", "X", "1", "A", null, null, null, "B", null, null, null, null, null, null);
        Segment noCode = new Segment(null, "Y", "2", "A", null, null, null, "B", null, null, null, null, null, null);
        Segment unknownCode = new Segment("QQ", "Z", "3", "A", null, null, null, "B", null, null, null, null, null, null);
        Direction refundable = new Direction("A-B", List.of(oddCode, noCode, unknownCode), null, null, true, null, null);
        List<PaxRow> stray = List.of(new PaxRow(" ", null, "Q-R", null, null));
        assertThat(text(data(stray, null, false, false, new TicketFacts(List.of(SEG), Map.of(), List.of(refundable))))).contains("Refundable");
        assertThat(text(data(stray, null, false, false, new TicketFacts(List.of(SEG), Map.of(), List.of())))).contains("IndiGo");
        assertThat(text(data(stray, null, false, false, new TicketFacts(List.of(), Map.of(), List.of())))).doesNotContain("IndiGo");
    }

    /** Writes the review samples to target/eticket-*.pdf (one-way, round trip with a markup, purged, test watermark). */
    @Test
    void writesSamples() throws Exception {
        byte[] logo = getClass().getResourceAsStream("/airlines/AI.png").readAllBytes();
        Direction spice = new Direction("DEL-BOM", List.of(new Segment("SG", "SpiceJet", "SG-8169", "DEL", "Delhi", "1D",
                "Indira Gandhi International Airport", "BOM", "Mumbai", "1", "Chhatrapati Shivaji Maharaj International Airport",
                "2026-11-05T06:15", "2026-11-05T08:25", 130)), "PUBLISHED", "ECONOMY", false, "15 KG", "7 KG");
        ETicketData oneWay = new ETicketData("Testing Travels", null, "+91 98765 43210", "Testing, Kanpur Uttar Pradesh", null,
                UUID, Instant.parse("2026-10-31T05:00:00Z"), "Confirmed", false, false,
                new TicketFacts(null, Map.of(), List.of(spice)), List.of(new PaxRow("Mr Rahul Sharma", "ADULT", "DEL-BOM", "MKEDOZ", "-")),
                List.of(new FareLine("Base fare", new BigDecimal("2980.00")), new FareLine("K3", new BigDecimal("152.00")),
                        new FareLine("YQ", new BigDecimal("600.00")), new FareLine("Other charges", new BigDecimal("79.40"))),
                new BigDecimal("3811.40"));
        Direction back = new Direction("GOI-DEL", List.of(new Segment("6E", "IndiGo", "6E-6", "GOI", "Goa", null, null, "BOM",
                "Mumbai", "1", null, "2026-11-09T16:20", "2026-11-09T17:35", 75), BACK_SEG), "PUBLISHED", "ECONOMY", true, "15 KG", "7 KG");
        Direction out = new Direction("DEL-GOI", List.of(SEG, new Segment("6E", "IndiGo", "6E-5", "BOM", "Mumbai", null, null,
                "GOI", "Goa", null, null, "2026-11-05T10:40", "2026-11-05T11:55", 75)), "PUBLISHED", "ECONOMY", false, "15 KG", "7 KG");
        ETicketData round = new ETicketData("Sky Travels", "bookings@skytravels.in", "+91 98765 43210", "12 Mall Road, Kanpur",
                logo, UUID, Instant.parse("2026-10-31T05:00:00Z"), "Confirmed", false, false,
                new TicketFacts(null, Map.of(), List.of(out, back)),
                List.of(new PaxRow("Mr Rahul Sharma", "ADULT", "DEL-GOI", "P1QX7Z", "098-2401234567"),
                        new PaxRow("Mr Rahul Sharma", "ADULT", "GOI-DEL", "K9LM2A", "775-2401234568"),
                        new PaxRow("Ms Asha Rao", "CHILD", "DEL-GOI", "P1QX7Z", "098-2401234569"),
                        new PaxRow("Ms Asha Rao", "CHILD", "GOI-DEL", "K9LM2A", "-")),
                List.of(new FareLine("Base fare", new BigDecimal("18250.00")), new FareLine("Taxes, fees and charges", new BigDecimal("6420.60"))),
                new BigDecimal("24670.60"));
        Path dir = Path.of("target");
        Files.createDirectories(dir);
        Files.write(dir.resolve("eticket-one-way.pdf"), ETicketPdfRenderer.render(oneWay));
        Files.write(dir.resolve("eticket-round-trip.pdf"), ETicketPdfRenderer.render(round));
        Files.write(dir.resolve("eticket-purged.pdf"), ETicketPdfRenderer.render(new ETicketData(oneWay.agencyName(), oneWay.email(),
                oneWay.phone(), oneWay.address(), null, UUID, oneWay.issuedAt(), "Cancelled", true, false, oneWay.facts(), null,
                oneWay.fare(), oneWay.total())));
        Files.write(dir.resolve("eticket-test.pdf"), ETicketPdfRenderer.render(new ETicketData(round.agencyName(), round.email(),
                round.phone(), round.address(), logo, UUID, round.issuedAt(), "Confirmed", false, true, round.facts(),
                round.passengers(), round.fare(), round.total())));
        assertThat(Files.size(dir.resolve("eticket-round-trip.pdf"))).isPositive();
    }
}
