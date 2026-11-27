package com.voyra.crm.util;

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
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPCellEvent;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import com.voyra.crm.enums.VisaFieldType;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The checklist an agency hands its client for one country's visa: the agency's header, the documents to provide
 * (File fields) and the details to share (every other field), each with a tick box and Required or Optional, and a
 * footer with the Voyra wordmark and the page number. Pure: all data comes in {@link ChecklistData}.
 */
public final class VisaChecklistPdfRenderer {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final float WIDTH = PageSize.A4.getWidth() - 72; // 36pt margins
    private static final Color TEAL = new Color(0x4A, 0x82, 0x85);
    private static final Color TEAL_DARK = new Color(0x2E, 0x5D, 0x60);
    private static final Color TINT = new Color(0xEA, 0xF2, 0xF2);
    private static final Color INK = new Color(0x1F, 0x2A, 0x30);
    private static final Color MUTED = new Color(0x6B, 0x7A, 0x80);
    private static final Color LINE = new Color(0xD6, 0xE0, 0xE0);
    private static final Color RED = new Color(0xB4, 0x23, 0x18);

    /** One line of the checklist. */
    public record Item(String label, VisaFieldType type, List<String> options, boolean required, String note) {

        public Item(String label, VisaFieldType type, List<String> options, boolean required) {
            this(label, type, options, required, null);
        }
    }

    public record ChecklistData(String agencyName, String email, String phone, String address, byte[] agencyLogo,
                                String country, LocalDate preparedOn, List<Item> items) {
    }

    private VisaChecklistPdfRenderer() {
    }

    public static byte[] render(ChecklistData d) {
        Document document = new Document(PageSize.A4, 36, 36, 32, 56);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter writer = PdfWriter.getInstance(document, out);
        writer.setPageEvent(new Footer(voyraWordmark()));
        document.open();
        document.add(header(d));
        document.add(intro(d.country()));
        List<Item> documents = d.items().stream().filter(i -> i.type() == VisaFieldType.FILE).toList();
        List<Item> details = d.items().stream().filter(i -> i.type() != VisaFieldType.FILE).toList();
        if (!documents.isEmpty()) {
            document.add(section("Documents to provide", documents.size()));
            document.add(rows(documents));
        }
        if (!details.isEmpty()) {
            document.add(section("Details we need from you", details.size()));
            document.add(rows(details));
        }
        document.add(closing(d));
        document.close();
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- header and intro

    private static PdfPTable header(ChecklistData d) {
        Image logo = image(d.agencyLogo());
        PdfPTable t;
        if (logo != null) {
            logo.scaleToFit(110, 40); // keeps the logo's own proportions
            float lw = logo.getScaledWidth() + 12;
            t = new PdfPTable(new float[]{lw, WIDTH - lw - 170, 170});
            t.addCell(rule(new PdfPCell(logo, false)));
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
        title.addElement(para("VISA CHECKLIST", font(true, 16, TEAL), Element.ALIGN_RIGHT));
        title.addElement(para("Prepared " + DAY.format(d.preparedOn()), font(false, 8, MUTED), Element.ALIGN_RIGHT));
        t.addCell(rule(title));
        return t;
    }

    private static PdfPTable intro(String country) {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        t.setSpacingBefore(14);
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.LEFT);
        c.setBorderColor(TEAL);
        c.setBorderWidth(3);
        c.setBackgroundColor(TINT);
        c.setPadding(10);
        c.setPaddingLeft(12);
        c.addElement(para(country + " visa", font(true, 14, TEAL_DARK), Element.ALIGN_LEFT));
        Paragraph p = para("Please keep the items below ready for your " + country + " visa application. Items marked "
                + "Required must be with us before we can apply; tick each one as you get it ready.",
                font(false, 9, INK), Element.ALIGN_LEFT);
        p.setSpacingBefore(3);
        c.addElement(p);
        t.addCell(c);
        return t;
    }

    // ---------------------------------------------------------------- sections

    private static PdfPTable section(String text, int count) {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        t.setSpacingBefore(16);
        t.setSpacingAfter(2);
        Paragraph p = para(text, font(true, 11, INK), Element.ALIGN_LEFT);
        p.add(new Phrase("   " + count + (count == 1 ? " item" : " items"), font(false, 8, MUTED)));
        PdfPCell c = new PdfPCell();
        c.addElement(p);
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(TEAL);
        c.setBorderWidth(0.8f);
        c.setPadding(0);
        c.setPaddingBottom(5);
        t.addCell(c);
        return t;
    }

    private static PdfPTable rows(List<Item> items) {
        PdfPTable t = new PdfPTable(new float[]{22, WIDTH - 22 - 70, 70});
        t.setTotalWidth(WIDTH);
        t.setLockedWidth(true);
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            boolean last = i == items.size() - 1;
            PdfPCell box = row(new PdfPCell(), last);
            box.setCellEvent(new TickBox());
            t.addCell(box);
            PdfPCell what = row(new PdfPCell(), last);
            what.addElement(para(item.label(), font(true, 10, INK), Element.ALIGN_LEFT));
            String hint = hint(item);
            if (hint != null) what.addElement(para(hint, font(false, 8, MUTED), Element.ALIGN_LEFT));
            t.addCell(what);
            PdfPCell need = row(new PdfPCell(), last);
            need.setPaddingTop(9); // lines the smaller word up with the label
            need.addElement(para(item.required() ? "Required" : "Optional",
                    font(item.required(), 8.5f, item.required() ? RED : MUTED), Element.ALIGN_RIGHT));
            t.addCell(need);
        }
        return t;
    }

    /** The item's own note, else what kind of answer the client should bring; a plain text answer or a document needs none. */
    static String hint(Item item) {
        if (item.note() != null) return item.note();
        return switch (item.type()) {
            case DATE -> "A date";
            case YES_NO -> "Yes or No";
            case DROPDOWN -> item.options() == null || item.options().isEmpty() ? null
                    : "One of: " + String.join(", ", item.options());
            default -> null;
        };
    }

    private static PdfPCell row(PdfPCell c, boolean last) {
        c.setBorder(last ? Rectangle.NO_BORDER : Rectangle.BOTTOM);
        c.setBorderColor(LINE);
        c.setBorderWidth(0.5f);
        c.setPadding(0);
        c.setPaddingTop(7);
        c.setPaddingBottom(8);
        c.setVerticalAlignment(Element.ALIGN_TOP);
        return c;
    }

    private static PdfPTable closing(ChecklistData d) {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        t.setSpacingBefore(20);
        t.setKeepTogether(true);
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.TOP);
        c.setBorderColor(LINE);
        c.setBorderWidth(0.5f);
        c.setPadding(0);
        c.setPaddingTop(8);
        String reach = join(" or ", d.phone(), d.email());
        c.addElement(para("Questions? Contact " + dash(d.agencyName()) + (reach.isEmpty() ? "" : " at " + reach) + ".",
                font(false, 8.5f, INK), Element.ALIGN_LEFT));
        Paragraph p = para("The embassy may ask for more at any stage; requirements can change without notice.",
                font(false, 7.5f, MUTED), Element.ALIGN_LEFT);
        p.setSpacingBefore(2);
        c.addElement(p);
        t.addCell(c);
        return t;
    }

    // ---------------------------------------------------------------- drawing

    /** An empty square at the top left of the cell, to tick by hand. */
    private static final class TickBox implements PdfPCellEvent {
        @Override
        public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
            PdfContentByte cb = canvases[PdfPTable.LINECANVAS];
            cb.saveState();
            cb.setColorStroke(TEAL);
            cb.setLineWidth(0.9f);
            cb.rectangle(position.getLeft() + 1, position.getTop() - 19, 11, 11);
            cb.stroke();
            cb.restoreState();
        }
    }

    /** "Prepared with" and the Voyra wordmark on the left, the page number on the right, on every page. */
    private static final class Footer extends PdfPageEventHelper {
        private final Image wordmark;

        private Footer(Image wordmark) {
            this.wordmark = wordmark;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            float y = 30;
            cb.saveState();
            cb.setColorStroke(LINE);
            cb.setLineWidth(0.5f);
            cb.moveTo(36, y + 16);
            cb.lineTo(PageSize.A4.getWidth() - 36, y + 16);
            cb.stroke();
            cb.restoreState();
            ColumnText.showTextAligned(cb, Element.ALIGN_LEFT, new Phrase("Prepared with", font(false, 7.5f, MUTED)), 36, y, 0);
            Image w = Image.getInstance(wordmark);
            w.scaleToFit(54, 13);
            w.setAbsolutePosition(36 + 52, y - 3);
            cb.addImage(w);
            ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT, new Phrase("Page " + writer.getPageNumber(), font(false, 7.5f, MUTED)),
                    PageSize.A4.getWidth() - 36, y, 0);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static PdfPCell rule(PdfPCell c) {
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(TEAL);
        c.setBorderWidth(1.2f);
        c.setPadding(0);
        c.setPaddingBottom(10);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return c;
    }

    private static Paragraph para(String text, Font font, int align) {
        Paragraph p = new Paragraph(text, font);
        p.setAlignment(align);
        p.setLeading(font.getSize() * 1.3f);
        return p;
    }

    private static Font font(boolean bold, float size, Color color) {
        return FontFactory.getFont(bold ? FontFactory.HELVETICA_BOLD : FontFactory.HELVETICA, size, color);
    }

    private static String join(String sep, String... parts) {
        return Stream.of(parts).filter(s -> s != null && !s.isBlank()).collect(Collectors.joining(sep));
    }

    private static String dash(String s) {
        return s == null || s.isBlank() ? "Your travel agency" : s;
    }

    @ExcludeFromJacocoGenerated // a classpath resource bundled in the jar; reading it declares IOException
    private static Image voyraWordmark() {
        try (InputStream in = VisaChecklistPdfRenderer.class.getResourceAsStream("/branding/voyra-wordmark.png")) {
            return Image.getInstance(in.readAllBytes());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Voyra wordmark missing from the build", e);
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
}
