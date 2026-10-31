package com.voyra.crm.util;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfGState;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import com.voyra.crm.service.booking.ticket.Direction;
import com.voyra.crm.service.booking.ticket.FareLine;
import com.voyra.crm.service.booking.ticket.Segment;
import com.voyra.crm.service.booking.ticket.TicketFacts;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * The customer's e-ticket for a platform flight: the agency's header, the flights of each direction with their PNR,
 * one passenger row per direction, the fare lines and total, and a fixed information list. Pure: all data comes in
 * {@link ETicketData}; the markup is never named, it sits inside the last fare line the caller built.
 */
public final class ETicketPdfRenderer {

    public static final String WATERMARK_CANCELLED = "NOT VALID FOR TRAVEL";
    public static final String WATERMARK_TEST = "TEST BOOKING - NOT VALID FOR TRAVEL";
    public static final String PURGED_TEXT = "Passenger details were removed after travel under the data-retention rule";

    private static final List<String> IMPORTANT = List.of(
            "Carry a valid photo ID (passport for international travel) for every passenger.",
            "Reach the airport at least 2 hours before a domestic and 3 hours before an international departure.",
            "Airline check-in counters close before departure; web check-in is done with the airline.",
            "Baggage allowance and rules are those of the airline.",
            "For changes, cancellations or refunds, contact your travel agency.");
    private static final DateTimeFormatter ISSUED = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm")
            .withZone(ZoneId.of("Asia/Kolkata"));
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final Color BAND = new Color(0xD9, 0xD9, 0xD9);

    public record ETicketData(String agencyName, String email, String phone, String address, byte[] agencyLogo,
                              String bookingId, Instant issuedAt, String statusText, boolean watermarkCancelled,
                              boolean watermarkTest, TicketFacts facts, List<PaxRow> passengers,
                              List<FareLine> fare, BigDecimal total) {
    }

    public record PaxRow(String name, String type, LocalDate dob, String passport, String nationality,
                         LocalDate passportExpiry, String sector, String pnr, String ticketNumber) {
    }

    private ETicketPdfRenderer() {
    }

    /** @param d {@code passengers} null means the passenger data was purged */
    public static byte[] render(ETicketData d) {
        Document document = new Document(PageSize.A4, 28, 28, 28, 28);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Font bold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8);
        Font body = FontFactory.getFont(FontFactory.HELVETICA, 8);
        Font small = FontFactory.getFont(FontFactory.HELVETICA, 7);
        Font name = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14);
        Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
        PdfWriter writer = PdfWriter.getInstance(document, out);
        String mark = d.watermarkCancelled() ? WATERMARK_CANCELLED : d.watermarkTest() ? WATERMARK_TEST : null;
        if (mark != null) {
            writer.setPageEvent(new Watermark(mark));
        }
        document.open();
        document.add(header(d, name, body));
        document.add(bookingLine(d, bold, body));

        List<Direction> directions = directions(d.facts());
        for (Direction dir : directions) {
            document.add(section("Flight " + dash(dir.key()) + "   PNR: " + pnrOf(d, dir), title));
            document.add(flights(dir, bold, small));
        }
        document.add(section("Passengers", title));
        if (d.passengers() == null) {
            document.add(plainTable(PURGED_TEXT, body));
        } else {
            document.add(passengers(d, directions, bold, small));
        }
        document.add(section("Fare details", title));
        document.add(fare(d, bold, body));
        document.add(section("Important information", title));
        for (String line : IMPORTANT) {
            document.add(plainTable("- " + line, body));
        }
        document.close();
        return out.toByteArray();
    }

    private static List<Direction> directions(TicketFacts facts) {
        if (facts == null) return List.of();
        if (facts.directions() != null && !facts.directions().isEmpty()) return facts.directions();
        return facts.segments() == null || facts.segments().isEmpty() ? List.of()
                : List.of(new Direction(null, facts.segments(), null, null, null, null, null));
    }

    private static String pnrOf(ETicketData d, Direction dir) {
        if (d.passengers() == null) return "-";
        return d.passengers().stream().filter(p -> Objects.equals(p.sector(), dir.key()))
                .map(PaxRow::pnr).filter(p -> !blank(p)).findFirst().orElse("-");
    }

    // ---------------------------------------------------------------- blocks

    private static PdfPTable header(ETicketData d, Font name, Font body) {
        Image img = image(d.agencyLogo());
        if (img != null) img.scaleToFit(60, 50);
        PdfPTable t = img != null ? new PdfPTable(new float[]{0.9f, 5f}) : new PdfPTable(1);
        t.setWidthPercentage(100);
        if (img != null) {
            PdfPCell logo = new PdfPCell(img);
            logo.setBorder(0);
            t.addCell(logo);
        }
        PdfPTable text = new PdfPTable(1);
        plain(text, dash(d.agencyName()).toUpperCase(), name);
        plain(text, dash(d.address()), body);
        plain(text, "Email: " + dash(d.email()) + "    Phone: " + dash(d.phone()), body);
        t.addCell(borderless(text));
        PdfPCell rule = new PdfPCell(new Phrase(" ", body));
        rule.setColspan(img != null ? 2 : 1);
        rule.setBorder(Rectangle.BOTTOM);
        rule.setFixedHeight(10);
        t.addCell(rule);
        return t;
    }

    private static PdfPTable bookingLine(ETicketData d, Font bold, Font body) {
        PdfPTable t = new PdfPTable(3);
        t.setWidthPercentage(100);
        t.setSpacingBefore(8);
        t.addCell(cell("E-TICKET   Booking: " + dash(d.bookingId()), bold, false));
        t.addCell(cell("Issued on: " + (d.issuedAt() == null ? "-" : ISSUED.format(d.issuedAt())), body, false));
        t.addCell(cell("Status: " + dash(d.statusText()), body, false));
        return t;
    }

    private static PdfPTable flights(Direction dir, Font bold, Font small) {
        PdfPTable t = new PdfPTable(new float[]{0.9f, 1.8f, 1f, 1f, 1f, 2.8f, 2.8f, 0.9f});
        t.setWidthPercentage(100);
        for (String h : List.of("Airline", "Flight", "Fare type", "Cabin", "Refundable", "Departure", "Arrival", "Duration")) {
            PdfPCell c = cell(h, bold, false);
            c.setBackgroundColor(BAND);
            t.addCell(c);
        }
        String refundable = dir.refundable() == null ? "-" : dir.refundable() ? "Refundable" : "Non-refundable";
        for (Segment s : dir.segments() == null ? List.<Segment>of() : dir.segments()) {
            Image logo = airlineLogo(s.airlineCode());
            PdfPCell logoCell = logo == null ? cell("", small, false) : new PdfPCell(logo, true);
            logoCell.setBorder(0);
            t.addCell(logoCell);
            t.addCell(cell(dash(s.airlineName()) + "\n" + dash(s.airlineCode()) + " " + dash(s.flightNumber()), small, false));
            t.addCell(cell(dash(dir.fareType()), small, false));
            t.addCell(cell(dash(dir.cabinClass()), small, false));
            t.addCell(cell(refundable, small, false));
            t.addCell(cell(place(s.departs(), s.fromCode(), s.fromCity(), s.fromTerminal(), s.fromAirport()), small, false));
            t.addCell(cell(place(s.arrives(), s.toCode(), s.toCity(), s.toTerminal(), s.toAirport()), small, false));
            t.addCell(cell(duration(s.durationMinutes()), small, false));
        }
        return t;
    }

    private static String place(String when, String code, String city, String terminal, String airport) {
        String where = dash(code) + (blank(city) ? "" : ", " + city) + (blank(terminal) ? "" : " T" + terminal);
        return dash(when == null ? null : when.replace('T', ' ')) + "\n" + where + (blank(airport) ? "" : "\n" + airport);
    }

    private static String duration(Integer minutes) {
        return minutes == null ? "-" : "%dh %02dm".formatted(minutes / 60, minutes % 60);
    }

    private static PdfPTable passengers(ETicketData d, List<Direction> directions, Font bold, Font small) {
        PdfPTable t = new PdfPTable(new float[]{2.2f, 0.9f, 1.3f, 1.4f, 1.3f, 1.3f, 1.1f, 1.1f, 1.8f, 1.8f});
        t.setWidthPercentage(100);
        for (String h : List.of("Passenger", "Type", "Date of birth", "Passport", "Nationality", "Passport expiry",
                "Sector", "PNR", "Ticket number", "Baggage")) {
            PdfPCell c = cell(h, bold, false);
            c.setBackgroundColor(BAND);
            t.addCell(c);
        }
        for (PaxRow p : d.passengers()) {
            Direction dir = directions.stream().filter(x -> Objects.equals(x.key(), p.sector())).findFirst().orElse(null);
            String baggage = dir == null ? "-" : "Check-in: " + dash(dir.checkInBaggage()) + "\nCabin: " + dash(dir.cabinBaggage());
            for (String v : new String[]{dash(p.name()), dash(p.type()), date(p.dob()), dash(p.passport()),
                    dash(p.nationality()), date(p.passportExpiry()), dash(p.sector()), dash(p.pnr()),
                    dash(p.ticketNumber()), baggage}) {
                t.addCell(cell(v, small, false));
            }
        }
        return t;
    }

    private static PdfPTable fare(ETicketData d, Font bold, Font body) {
        PdfPTable t = new PdfPTable(new float[]{4f, 1.5f});
        t.setWidthPercentage(60);
        t.setHorizontalAlignment(Element.ALIGN_LEFT);
        for (FareLine l : d.fare() == null ? List.<FareLine>of() : d.fare()) {
            t.addCell(cell(dash(l.label()), body, false));
            t.addCell(cell(money(l.amount()), body, true));
        }
        t.addCell(cell("Total fare", bold, false));
        t.addCell(cell(money(d.total()), bold, true));
        return t;
    }

    private static String money(BigDecimal v) {
        return v == null ? "-" : "INR " + v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    // ---------------------------------------------------------------- helpers

    private static PdfPTable section(String text, Font font) {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        t.setSpacingBefore(10);
        plain(t, text, font);
        return t;
    }

    private static PdfPTable plainTable(String text, Font font) {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        plain(t, text, font);
        return t;
    }

    private static void plain(PdfPTable t, String text, Font font) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setBorder(0);
        t.addCell(c);
    }

    private static PdfPCell borderless(PdfPTable inner) {
        PdfPCell c = new PdfPCell(inner);
        c.setBorder(0);
        return c;
    }

    private static PdfPCell cell(String text, Font font, boolean right) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(Color.LIGHT_GRAY);
        c.setPadding(3);
        if (right) c.setHorizontalAlignment(Element.ALIGN_RIGHT);
        return c;
    }

    private static String date(LocalDate v) {
        return v == null ? "-" : DAY.format(v);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String dash(String s) {
        return blank(s) ? "-" : s;
    }

    private static Image airlineLogo(String code) {
        if (blank(code) || !code.matches("[A-Za-z0-9]{2,3}")) return null;
        return image(logoBytes("/airlines/" + code.toUpperCase() + ".png"));
    }

    @ExcludeFromJacocoGenerated // readAllBytes declares IOException; a classpath stream cannot fail in a test
    private static byte[] logoBytes(String path) {
        try (InputStream in = ETicketPdfRenderer.class.getResourceAsStream(path)) {
            return in == null ? null : in.readAllBytes();
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** An image OpenPDF cannot read (WebP, junk) is simply not printed. */
    private static Image image(byte[] bytes) {
        if (bytes == null) return null;
        try {
            return Image.getInstance(bytes);
        } catch (Exception e) {
            return null;
        }
    }

    private static final class Watermark extends PdfPageEventHelper {
        private final String text;

        Watermark(String text) {
            this.text = text;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            PdfGState gs = new PdfGState();
            gs.setFillOpacity(0.18f);
            cb.saveState();
            cb.setGState(gs);
            ColumnText.showTextAligned(cb, Element.ALIGN_CENTER,
                    new Phrase(text, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 30, Color.RED)),
                    PageSize.A4.getWidth() / 2, PageSize.A4.getHeight() / 2, 45);
            cb.restoreState();
        }
    }
}
