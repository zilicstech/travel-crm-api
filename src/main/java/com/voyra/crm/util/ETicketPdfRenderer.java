package com.voyra.crm.util;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The customer's e-ticket for a platform flight: the agency's header, a band with the PNR, booking reference and status,
 * one card per direction, the passengers, the fare lines and total, and a fixed information list. Pure: all data comes
 * in {@link ETicketData}; the markup is never named, it sits inside the fare lines the caller built.
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
    private static final DateTimeFormatter ISSUED = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.ENGLISH)
            .withZone(ZoneId.of("Asia/Kolkata"));
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter WEEKDAY = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy", Locale.ENGLISH);

    private static final float WIDTH = PageSize.A4.getWidth() - 72; // 36pt margins
    private static final Color TEAL = new Color(0x4A, 0x82, 0x85);
    private static final Color TEAL_DARK = new Color(0x2E, 0x5D, 0x60);
    private static final Color TINT = new Color(0xEA, 0xF2, 0xF2);
    private static final Color SOFT = new Color(0xF6, 0xF9, 0xF9);
    private static final Color INK = new Color(0x1F, 0x2A, 0x30);
    private static final Color MUTED = new Color(0x6B, 0x7A, 0x80);
    private static final Color LINE = new Color(0xD6, 0xE0, 0xE0);
    private static final Color RED = new Color(0xB4, 0x23, 0x18);

    public record ETicketData(String agencyName, String email, String phone, String address, byte[] agencyLogo,
                              String bookingId, Instant issuedAt, String statusText, boolean watermarkCancelled,
                              boolean watermarkTest, TicketFacts facts, List<PaxRow> passengers,
                              List<FareLine> fare, BigDecimal total) {
    }

    /** What the ticket prints of one passenger in one direction; no identity documents (owner ruling 2026-11-01). */
    public record PaxRow(String name, String type, String sector, String pnr, String ticketNumber) {
    }

    private record Col(String head, float width, Font font, boolean perPerson, Function<PaxRow, String> value) {
    }

    private ETicketPdfRenderer() {
    }

    /** @param d {@code passengers} null means the passenger data was purged */
    public static byte[] render(ETicketData d) {
        Document document = new Document(PageSize.A4, 36, 36, 32, 36);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter writer = PdfWriter.getInstance(document, out);
        String mark = d.watermarkCancelled() ? WATERMARK_CANCELLED : d.watermarkTest() ? WATERMARK_TEST : null;
        if (mark != null) {
            writer.setPageEvent(new Watermark(mark));
        }
        document.open();
        document.add(header(d));
        List<Direction> directions = directions(d.facts());
        Map<String, String> pnrs = new LinkedHashMap<>();
        directions.forEach(dir -> pnrs.put(dir.key(), pnrOf(d, dir)));
        document.add(band(d, pnrs));
        for (Direction dir : directions) {
            document.add(card(dir));
        }
        document.add(section("Passengers"));
        document.add(d.passengers() == null ? note(PURGED_TEXT) : passengers(d.passengers(), directions));
        document.add(section("Fare summary"));
        document.add(fare(d));
        document.add(important());
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

    // ---------------------------------------------------------------- header and band

    private static PdfPTable header(ETicketData d) {
        Image logo = image(d.agencyLogo());
        PdfPTable t;
        if (logo != null) {
            logo.scaleToFit(110, 40); // keeps the logo's own proportions
            float lw = logo.getScaledWidth() + 12;
            t = new PdfPTable(new float[]{lw, WIDTH - lw - 170, 170});
            PdfPCell c = new PdfPCell(logo, false);
            t.addCell(rule(c));
        } else {
            t = new PdfPTable(new float[]{WIDTH - 170, 170});
        }
        t.setTotalWidth(WIDTH);
        t.setLockedWidth(true);
        PdfPCell agency = new PdfPCell();
        agency.addElement(para(dash(d.agencyName()), font(true, 15, INK), Element.ALIGN_LEFT));
        String contact = join("   ·   ", d.address(), d.email(), d.phone());
        if (!contact.isEmpty()) agency.addElement(para(contact, font(false, 8, MUTED), Element.ALIGN_LEFT));
        t.addCell(rule(agency));
        PdfPCell title = new PdfPCell();
        title.addElement(para("E-TICKET", font(true, 18, TEAL), Element.ALIGN_RIGHT));
        title.addElement(para("Issued " + (d.issuedAt() == null ? "-" : ISSUED.format(d.issuedAt())), font(false, 8, MUTED), Element.ALIGN_RIGHT));
        t.addCell(rule(title));
        return t;
    }

    private static PdfPCell rule(PdfPCell c) {
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(TEAL);
        c.setBorderWidth(1.2f);
        c.setPadding(0);
        c.setPaddingBottom(10);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return c;
    }

    /** PNR, booking reference and status; one PNR when every direction shares it, else one line per direction. */
    private static PdfPTable band(ETicketData d, Map<String, String> pnrs) {
        PdfPTable t = new PdfPTable(new float[]{1.5f, 1.8f, 1f});
        t.setWidthPercentage(100);
        t.setSpacingBefore(12);
        PdfPCell pnr = bandCell("PNR");
        List<String> values = pnrs.values().stream().distinct().toList();
        if (values.size() <= 1) {
            pnr.addElement(para(values.isEmpty() ? "-" : values.get(0), font(true, 18, TEAL_DARK), Element.ALIGN_LEFT));
        } else {
            pnrs.forEach((k, v) -> {
                Paragraph p = para(dash(k) + "   ", font(false, 8, MUTED), Element.ALIGN_LEFT);
                p.add(new Chunk(v, font(true, 12, TEAL_DARK)));
                pnr.addElement(p);
            });
        }
        t.addCell(pnr);
        PdfPCell ref = bandCell("Booking reference");
        ref.addElement(para(dash(d.bookingId()), FontFactory.getFont(FontFactory.COURIER, 8, INK), Element.ALIGN_LEFT));
        t.addCell(ref);
        PdfPCell status = bandCell("Status");
        status.addElement(para(dash(d.statusText()), font(true, 11, d.watermarkCancelled() ? RED : TEAL_DARK), Element.ALIGN_LEFT));
        t.addCell(status);
        return t;
    }

    private static PdfPCell bandCell(String label) {
        PdfPCell c = new PdfPCell();
        c.setBorder(0);
        c.setBackgroundColor(TINT);
        c.setPadding(10);
        c.setPaddingTop(7);
        c.addElement(label(label));
        return c;
    }

    // ---------------------------------------------------------------- flights

    /** One boxed card per direction: a heading, a row per segment with the change of plane between, and the fare facts. */
    private static PdfPTable card(Direction dir) {
        List<Segment> segs = dir.segments() == null ? List.of() : dir.segments();
        PdfPTable inner = new PdfPTable(1);
        inner.setWidthPercentage(100);
        PdfPTable head = new PdfPTable(new float[]{3f, 2f});
        head.setWidthPercentage(100);
        String title = dash(dir.key()), sub = "";
        if (!segs.isEmpty()) {
            Segment first = segs.get(0), last = segs.get(segs.size() - 1);
            LocalDateTime departs = at(first.departs());
            title = cityOr(first.fromCity(), first.fromCode()) + " to " + cityOr(last.toCity(), last.toCode());
            sub = join("   ·   ", departs == null ? null : WEEKDAY.format(departs), stops(segs.size() - 1));
        }
        head.addCell(pad(new PdfPCell(new Phrase(title, font(true, 10.5f, INK))), 0));
        PdfPCell right = pad(new PdfPCell(new Phrase(sub, font(false, 8, MUTED))), 0);
        right.setHorizontalAlignment(Element.ALIGN_RIGHT);
        head.addCell(right);
        PdfPCell headCell = pad(new PdfPCell(head), 8);
        headCell.setPaddingLeft(10);
        headCell.setPaddingRight(10);
        headCell.setBackgroundColor(SOFT);
        inner.addCell(headCell);
        for (int i = 0; i < segs.size(); i++) {
            if (i > 0) inner.addCell(layover(segs.get(i - 1), segs.get(i)));
            inner.addCell(pad(new PdfPCell(segment(segs.get(i))), 0));
        }
        List<String[]> facts = facts(dir);
        if (!facts.isEmpty()) {
            PdfPTable f = new PdfPTable(facts.size());
            f.setWidthPercentage(100);
            for (String[] fact : facts) {
                PdfPCell c = pad(new PdfPCell(), 0);
                c.addElement(label(fact[0]));
                c.addElement(para(fact[1], font(true, 8.5f, INK), Element.ALIGN_LEFT));
                f.addCell(c);
            }
            PdfPCell fc = pad(new PdfPCell(f), 8);
            fc.setPaddingLeft(10);
            fc.setBorder(Rectangle.TOP);
            fc.setBorderColor(LINE);
            inner.addCell(fc);
        }
        PdfPTable outer = new PdfPTable(1);
        outer.setWidthPercentage(100);
        outer.setSpacingBefore(8);
        outer.setKeepTogether(true);
        PdfPCell box = new PdfPCell(inner);
        box.setBorderColor(LINE);
        box.setBorderWidth(0.75f);
        box.setPadding(0);
        outer.addCell(box);
        return outer;
    }

    private static PdfPTable segment(Segment s) {
        PdfPTable t = new PdfPTable(new float[]{2.3f, 2.6f, 1.5f, 2.6f});
        t.setWidthPercentage(100);
        t.addCell(airline(s));
        t.addCell(end(s.departs(), s.fromCode(), s.fromCity(), s.fromTerminal(), s.fromAirport(), Element.ALIGN_LEFT));
        PdfPTable line = new PdfPTable(1);
        PdfPCell dur = pad(new PdfPCell(new Phrase(duration(s.durationMinutes()), font(false, 7.5f, MUTED))), 0);
        dur.setHorizontalAlignment(Element.ALIGN_CENTER);
        dur.setPaddingBottom(4);
        dur.setBorder(Rectangle.BOTTOM);
        dur.setBorderColor(TEAL);
        dur.setBorderWidth(0.8f);
        line.addCell(dur);
        PdfPCell mid = pad(new PdfPCell(line), 12);
        mid.setPaddingTop(14);
        t.addCell(mid);
        t.addCell(end(s.arrives(), s.toCode(), s.toCity(), s.toTerminal(), s.toAirport(), Element.ALIGN_RIGHT));
        return t;
    }

    /** A small airline mark at a fixed height (its proportions kept) beside the airline name and flight number. */
    private static PdfPCell airline(Segment s) {
        PdfPCell text = pad(new PdfPCell(), 0);
        text.addElement(para(dash(s.airlineName()), font(true, 8.5f, INK), Element.ALIGN_LEFT));
        text.addElement(para(flightNo(s), font(false, 8, MUTED), Element.ALIGN_LEFT));
        text.setVerticalAlignment(Element.ALIGN_MIDDLE);
        Image logo = airlineLogo(s.airlineCode());
        if (logo == null) return pad(new PdfPCell(text), 8);
        logo.scaleToFit(22, 22);
        PdfPTable t = new PdfPTable(new float[]{1f, 3.4f});
        t.setWidthPercentage(100);
        PdfPCell l = pad(new PdfPCell(logo, false), 0);
        l.setVerticalAlignment(Element.ALIGN_MIDDLE);
        t.addCell(l);
        t.addCell(text);
        return pad(new PdfPCell(t), 8);
    }

    /** Time large, then code and city, the date, and the airport with its terminal. */
    private static PdfPCell end(String when, String code, String city, String terminal, String airport, int align) {
        LocalDateTime at = at(when);
        PdfPCell c = pad(new PdfPCell(), 8);
        c.addElement(para(at == null ? dash(when) : TIME.format(at), font(true, 17, INK), align));
        c.addElement(para(join("  ", dash(code), city), font(true, 8.5f, INK), align));
        if (at != null) c.addElement(para(WEEKDAY.format(at), font(false, 7.5f, MUTED), align));
        String where = join(", ", airport, blank(terminal) ? null : "Terminal " + terminal);
        if (!where.isEmpty()) c.addElement(para(where, font(false, 7, MUTED), align));
        return c;
    }

    private static PdfPCell layover(Segment prev, Segment next) {
        LocalDateTime landed = at(prev.arrives()), leaves = at(next.departs());
        String place = blank(next.fromCity()) ? dash(next.fromCode()) : next.fromCity() + " (" + dash(next.fromCode()) + ")";
        String text = join("   ·   ", "Change of plane at " + place,
                landed == null || leaves == null ? null : "Layover " + duration((int) Duration.between(landed, leaves).toMinutes()));
        PdfPCell c = pad(new PdfPCell(new Phrase(text, font(false, 7.5f, MUTED))), 5);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        c.setBackgroundColor(SOFT);
        c.setBorder(Rectangle.TOP | Rectangle.BOTTOM);
        c.setBorderColor(LINE);
        return c;
    }

    /** The fare facts the supplier gave, as label and value; unknown ones are left out. */
    private static List<String[]> facts(Direction dir) {
        List<String[]> out = new ArrayList<>();
        String[][] all = {{"Fare type", words(dir.fareType())}, {"Cabin", words(dir.cabinClass())},
                {"Refund", dir.refundable() == null ? null : dir.refundable() ? "Refundable" : "Non-refundable"},
                {"Check-in baggage", dir.checkInBaggage()}, {"Cabin baggage", dir.cabinBaggage()}};
        for (String[] f : all) {
            if (!blank(f[1])) out.add(f);
        }
        return out;
    }

    private static String stops(int n) {
        return n == 0 ? "Non-stop" : n + (n == 1 ? " stop" : " stops");
    }

    /** Supplier codes for the customer: "PUBLISHED" as "Published", "SPECIAL_RETURN" as "Special Return"; short ones ("SME") are acronyms. */
    private static String words(String code) {
        if (blank(code)) return null;
        return Arrays.stream(code.trim().split("[_\\s]+"))
                .map(w -> w.length() <= 3 ? w : w.substring(0, 1).toUpperCase(Locale.ENGLISH) + w.substring(1).toLowerCase(Locale.ENGLISH))
                .collect(Collectors.joining(" "));
    }

    private static String flightNo(Segment s) {
        String code = s.airlineCode(), no = s.flightNumber();
        if (blank(no)) return dash(code);
        return blank(code) || no.startsWith(code) ? no : code + " " + no;
    }

    private static String cityOr(String city, String code) {
        return blank(city) ? dash(code) : city;
    }

    /** "2026-11-05T06:15[:ss]", no zone shift; null when absent or not ISO (the raw text is then printed as given). */
    private static LocalDateTime at(String iso) {
        try {
            return LocalDateTime.parse(iso);
        } catch (RuntimeException e) { // null or not ISO
            return null;
        }
    }

    private static String duration(Integer minutes) {
        return minutes == null ? "-" : "%dh %02dm".formatted(minutes / 60, minutes % 60);
    }

    // ---------------------------------------------------------------- passengers

    /**
     * One row per passenger and direction, the passenger's own cells spanning their directions; the sector column only
     * when the trip has more than one.
     */
    private static PdfPTable passengers(List<PaxRow> rows, List<Direction> directions) {
        Font name = font(true, 8.5f, INK), body = font(false, 8, INK);
        List<Col> cols = new ArrayList<>();
        cols.add(new Col("Passenger", 3f, name, true, p -> dash(p.name())));
        cols.add(new Col("Type", 1.2f, body, true, p -> dash(words(p.type()))));
        boolean oneSector = directions.size() == 1 && rows.stream().allMatch(p -> Objects.equals(p.sector(), directions.get(0).key()));
        if (!oneSector) cols.add(new Col("Sector", 1.4f, body, false, p -> dash(p.sector())));
        cols.add(new Col("PNR", 1.6f, body, false, p -> dash(p.pnr())));
        cols.add(new Col("Ticket number", 2.2f, body, false, p -> dash(p.ticketNumber())));

        float[] widths = new float[cols.size()];
        for (int i = 0; i < widths.length; i++) widths[i] = cols.get(i).width();
        PdfPTable t = new PdfPTable(widths);
        t.setWidthPercentage(100);
        t.setHeaderRows(1);
        for (Col c : cols) {
            PdfPCell h = row(new PdfPCell());
            h.addElement(label(c.head()));
            t.addCell(h);
        }
        for (List<PaxRow> group : people(rows)) {
            for (int j = 0; j < group.size(); j++) {
                for (Col c : cols) {
                    if (c.perPerson() && j > 0) continue;
                    PdfPCell cell = row(new PdfPCell(new Phrase(c.value().apply(group.get(j)), c.font())));
                    if (c.perPerson()) cell.setRowspan(group.size());
                    t.addCell(cell);
                }
            }
        }
        return t;
    }

    /** Consecutive rows of the same person, one per sector (a second row for a sector already seen is another person). */
    private static List<List<PaxRow>> people(List<PaxRow> rows) {
        List<List<PaxRow>> groups = new ArrayList<>();
        for (PaxRow p : rows) {
            List<PaxRow> last = groups.isEmpty() ? null : groups.get(groups.size() - 1);
            if (last != null && person(last.get(0)).equals(person(p)) && last.stream().noneMatch(x -> Objects.equals(x.sector(), p.sector()))) {
                last.add(p);
            } else {
                groups.add(new ArrayList<>(List.of(p)));
            }
        }
        return groups;
    }

    private static PaxRow person(PaxRow p) {
        return new PaxRow(p.name(), p.type(), null, null, null);
    }

    private static PdfPCell row(PdfPCell c) {
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(LINE);
        c.setBorderWidth(0.5f);
        c.setPadding(5);
        c.setPaddingTop(4);
        c.setPaddingLeft(2);
        return c;
    }

    // ---------------------------------------------------------------- fare and notes

    private static PdfPTable fare(ETicketData d) {
        PdfPTable t = new PdfPTable(new float[]{3f, 1.4f});
        t.setWidthPercentage(58);
        t.setHorizontalAlignment(Element.ALIGN_LEFT);
        Font body = font(false, 8.5f, INK);
        for (FareLine l : d.fare() == null ? List.<FareLine>of() : d.fare()) {
            t.addCell(row(new PdfPCell(new Phrase(dash(l.label()), body))));
            PdfPCell amount = row(new PdfPCell(new Phrase(money(l.amount()), body)));
            amount.setHorizontalAlignment(Element.ALIGN_RIGHT);
            t.addCell(amount);
        }
        PdfPCell label = new PdfPCell(new Phrase("Total fare", font(true, 10, TEAL_DARK)));
        PdfPCell total = new PdfPCell(new Phrase(money(d.total()), font(true, 12, TEAL_DARK)));
        total.setHorizontalAlignment(Element.ALIGN_RIGHT);
        for (PdfPCell c : List.of(label, total)) {
            c.setBorder(Rectangle.TOP);
            c.setBorderColor(TEAL);
            c.setBorderWidth(1);
            c.setBackgroundColor(TINT);
            c.setPadding(7);
            c.setVerticalAlignment(Element.ALIGN_MIDDLE);
            t.addCell(c);
        }
        return t;
    }

    /** "INR 3,81,140.00": Indian digit grouping (last three, then pairs), two decimals. */
    static String money(BigDecimal v) {
        if (v == null) return "-";
        String s = v.setScale(2, RoundingMode.HALF_UP).abs().toPlainString();
        int dot = s.indexOf('.');
        StringBuilder whole = new StringBuilder(s.substring(0, dot));
        for (int i = whole.length() - 3; i > 0; i -= 2) whole.insert(i, ',');
        return "INR " + (v.signum() < 0 ? "-" : "") + whole + s.substring(dot);
    }

    /** Heading and list in one block, so a page break never orphans the heading. */
    private static PdfPTable important() {
        PdfPTable t = section("Important information");
        t.setKeepTogether(true);
        PdfPCell c = pad(new PdfPCell(), 0);
        c.setPaddingTop(6);
        for (String line : IMPORTANT) {
            Paragraph p = para("•  " + line, font(false, 7.5f, MUTED), Element.ALIGN_LEFT);
            p.setSpacingAfter(2);
            c.addElement(p);
        }
        t.addCell(c);
        return t;
    }

    private static PdfPTable note(String text) {
        PdfPCell c = new PdfPCell(new Phrase(text, font(false, 8.5f, MUTED)));
        c.setBorder(0);
        c.setBackgroundColor(SOFT);
        c.setPadding(10);
        return wrap(c);
    }

    // ---------------------------------------------------------------- helpers

    private static PdfPTable section(String text) {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        t.setSpacingBefore(14);
        t.setSpacingAfter(4);
        PdfPCell c = new PdfPCell(new Phrase(text, font(true, 10.5f, INK)));
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(LINE);
        c.setBorderWidth(0.5f);
        c.setPadding(0);
        c.setPaddingBottom(5);
        t.addCell(c);
        return t;
    }

    private static PdfPTable wrap(PdfPCell c) {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        t.addCell(c);
        return t;
    }

    private static PdfPCell pad(PdfPCell c, float padding) {
        c.setBorder(0);
        c.setPadding(padding);
        return c;
    }

    private static Paragraph label(String text) {
        Chunk c = new Chunk(text.toUpperCase(Locale.ENGLISH), font(true, 6.5f, MUTED));
        c.setCharacterSpacing(0.5f);
        Paragraph p = new Paragraph(c);
        p.setLeading(9);
        return p;
    }

    private static Paragraph para(String text, Font font, int align) {
        Paragraph p = new Paragraph(text, font);
        p.setLeading(font.getSize() * 1.3f);
        p.setAlignment(align);
        return p;
    }

    private static Font font(boolean bold, float size, Color color) {
        return FontFactory.getFont(bold ? FontFactory.HELVETICA_BOLD : FontFactory.HELVETICA, size, color);
    }

    /** The non-blank parts joined, or "" when there are none. */
    private static String join(String sep, String... parts) {
        return Stream.of(parts).filter(s -> !blank(s)).collect(Collectors.joining(sep));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String dash(String s) {
        return blank(s) ? "-" : s;
    }

    private static Image airlineLogo(String code) {
        if (blank(code) || !code.matches("[A-Za-z0-9]{2,3}")) return null;
        InputStream in = ETicketPdfRenderer.class.getResourceAsStream("/airlines/" + code.toUpperCase() + ".png");
        return in == null ? null : image(readAll(in));
    }

    @ExcludeFromJacocoGenerated // readAllBytes declares IOException; a classpath stream cannot fail in a test
    private static byte[] readAll(InputStream in) {
        try (in) {
            return in.readAllBytes();
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
            gs.setFillOpacity(0.08f);
            cb.saveState();
            cb.setGState(gs);
            float size = Math.min(54f, 620f / (text.length() * 0.62f)); // roughly fits the page diagonal
            ColumnText.showTextAligned(cb, Element.ALIGN_CENTER,
                    new Phrase(text, FontFactory.getFont(FontFactory.HELVETICA_BOLD, size, RED)),
                    PageSize.A4.getWidth() / 2, PageSize.A4.getHeight() / 2, 45);
            cb.restoreState();
        }
    }
}
