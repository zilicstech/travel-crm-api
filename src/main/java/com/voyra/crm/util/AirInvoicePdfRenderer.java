package com.voyra.crm.util;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.voyra.crm.dto.VoucherLeg;
import com.voyra.crm.dto.VoucherTraveller;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.entity.InvoiceLineItem;
import com.voyra.crm.entity.InvoiceTax;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.enums.InvoiceServiceCategory;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The air ticket tax invoice, laid out as the agency's old software printed it: one block per ticket
 * (PNR) naming every passenger and every flight, its fare as Basic / YQ / Other / Total, then the GST
 * on the agency's markup as CGST and SGST (or IGST) rows. The markup itself is not a line: it sits in
 * Other, so each row's Basic + YQ + Other is its Total and the rows add up to the Gross Total.
 * Everything printed is frozen on the invoice and its lines, except the agency's contact details and
 * logo (read fresh, as the bank details are).
 *
 * <p>A hotel invoice is printed the same way, with a stay table instead of the flights: one block per
 * hotel voucher - guests, hotel, check-in, check-out, nights, rooms and its confirmation number.
 */
public final class AirInvoicePdfRenderer {

    private static final DateTimeFormatter INVOICE_DATE = DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final DateTimeFormatter TRAVEL_DATE = DateTimeFormatter.ofPattern("dd/MM/yy");
    private static final Color BAND = new Color(0xD9, 0xD9, 0xD9);
    private static final Map<String, String> CABINS = Map.of(
            "ECONOMY", "ECO", "PREMIUM ECONOMY", "PEC", "BUSINESS", "BUS", "FIRST", "FST");

    private AirInvoicePdfRenderer() {
    }

    /** An air invoice drafted from tickets that carry their flights; older ones keep the general layout. */
    public static boolean applies(InvoiceServiceCategory category, List<InvoiceLineItem> lines) {
        return (category == InvoiceServiceCategory.AIR_INTERNATIONAL || category == InvoiceServiceCategory.AIR_DOMESTIC)
                && lines.stream().anyMatch(AirInvoicePdfRenderer::hasLegs);
    }

    /**
     * @param references each voucher's reference (PNR), by voucher id
     * @param logo       the agency's logo image bytes, or null
     * @param preparedBy who generated the invoice
     */
    public static byte[] write(Invoice invoice, List<InvoiceLineItem> lines, List<InvoiceTax> taxes, Tenant agency,
                               Map<String, String> references, byte[] logo, String preparedBy) {
        Set<String> airlines = new LinkedHashSet<>();
        lines.stream().filter(AirInvoicePdfRenderer::hasLegs).flatMap(l -> l.getLegs().stream())
                .map(VoucherLeg::getAirline).filter(a -> a != null && !a.isBlank()).forEach(airlines::add);
        return render(invoice, lines, taxes, agency, references, logo, preparedBy, "Airline PNR",
                airlines.isEmpty() ? null : "Airline: " + String.join(", ", airlines),
                body -> flights(lines, references, body), "(Reimbursement of air ticket issued by airlines)");
    }

    /** A hotel invoice drafted from hotel vouchers; one typed by hand keeps the general layout. */
    public static boolean appliesToHotel(InvoiceServiceCategory category, List<InvoiceLineItem> lines) {
        return category == InvoiceServiceCategory.HOTEL && lines.stream().anyMatch(l -> l.getBookingDocumentId() != null);
    }

    /**
     * @param details each voucher's details (hotelName, hotelCity, hotelCheckIn, hotelCheckOut, hotelRooms,
     *                hotelRoomType, hotelBoardBasis), by voucher id
     */
    public static byte[] writeHotel(Invoice invoice, List<InvoiceLineItem> lines, List<InvoiceTax> taxes, Tenant agency,
                                    Map<String, String> references, Map<String, Map<String, String>> details,
                                    byte[] logo, String preparedBy) {
        return render(invoice, lines, taxes, agency, references, logo, preparedBy, "Confirmation No.", null,
                body -> stays(invoice, lines, references, details, body), "(Reimbursement of hotel accommodation)");
    }

    private static byte[] render(Invoice invoice, List<InvoiceLineItem> lines, List<InvoiceTax> taxes, Tenant agency,
                                 Map<String, String> references, byte[] logo, String preparedBy, String referenceLabel,
                                 String aboveTable, java.util.function.Function<Font, PdfPTable> table, String reimbursement) {
        Document document = new Document(PageSize.A4, 28, 28, 28, 28);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Font bold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8);
        Font body = FontFactory.getFont(FontFactory.HELVETICA, 8);
        Font small = FontFactory.getFont(FontFactory.HELVETICA, 6.5f);
        Font name = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);
        Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
        Font pnr = FontFactory.getFont(FontFactory.HELVETICA_BOLDOBLIQUE, 9);
        String base = InvoicePdfRenderer.base(agency);
        // no finally: closing a document with nothing on it throws "The document has no pages" and would hide the real error
        PdfWriter.getInstance(document, out);
        document.open();

        document.add(header(invoice, agency, logo, name, body));
        List<String> refs = lines.stream().map(l -> references.get(l.getBookingDocumentId()))
                .filter(r -> r != null && !r.isBlank()).distinct().toList();
        document.add(billTo(invoice, referenceLabel, refs, bold, body, title, pnr));
        if (aboveTable != null) {
            document.add(single(aboveTable, bold, 0));
        }
        document.add(table.apply(body));
        document.add(spacer(28));

        BigDecimal gross = lines.stream().map(l -> nz(l.getTaxableValue())).reduce(BigDecimal.ZERO, BigDecimal::add);
        document.add(totals(invoice, taxes, gross, base, agency, reimbursement, bold, body));
        document.add(terms(invoice, agency, bold, small, body));
        document.add(footer(invoice, preparedBy, body, small));
        document.close();
        return out.toByteArray();
    }

    private static PdfPTable flights(List<InvoiceLineItem> lines, Map<String, String> references, Font body) {
        PdfPTable table = new PdfPTable(new float[]{3.2f, 1.9f, 1f, 1f, 1.1f, 1.1f, 0.9f, 1f, 1.3f});
        table.setWidthPercentage(100);
        table.setSpacingBefore(4);
        for (String h : List.of("Passenger", "Sector", "Flight", "Travel On", "Airline PNR", "Basic", "YQ", "Other", "Total")) {
            PdfPCell c = cell(h, body, h.equals("Basic") || h.equals("YQ") || h.equals("Other") || h.equals("Total"));
            c.setBackgroundColor(BAND);
            table.addCell(c);
        }
        int passenger = 0;
        for (InvoiceLineItem line : lines) {
            BigDecimal total = nz(line.getTaxableValue());
            Map<String, BigDecimal> charges = line.getCharges();
            BigDecimal basic = charges != null ? nz(charges.get("basic")) : total;
            BigDecimal yq = charges != null ? nz(charges.get("yq")) : BigDecimal.ZERO;
            BigDecimal other = total.subtract(basic).subtract(yq);
            List<VoucherTraveller> travellers = line.getTravellers() != null ? line.getTravellers() : List.of();
            List<VoucherLeg> legs = line.getLegs() != null ? line.getLegs() : List.of();
            String reference = valueOr(references.get(line.getBookingDocumentId()));
            int rows = Math.max(1, Math.max(travellers.size(), legs.size()));
            for (int i = 0; i < rows; i++) {
                String who = i < travellers.size() ? "%03d %s".formatted(++passenger, upper(travellers.get(i).getName()))
                        : (i == 0 ? valueOr(line.getDescription()) : ""); // row 0 past the travellers means there are none
                VoucherLeg leg = i < legs.size() ? legs.get(i) : null;
                table.addCell(cell(who, body, false));
                table.addCell(cell(leg == null ? "" : String.join("  ", nn(leg.getFrom()), nn(leg.getTo()), cabin(leg.getCabin())).trim(), body, false));
                table.addCell(cell(leg == null ? "" : nn(leg.getFlightNumber()), body, false));
                table.addCell(cell(leg == null || leg.getTravelDate() == null ? "" : leg.getTravelDate().format(TRAVEL_DATE), body, false));
                table.addCell(cell(leg == null ? "" : reference, body, false));
                boolean first = i == 0;
                table.addCell(cell(first ? amount(basic) : "", body, true));
                table.addCell(cell(first && yq.signum() != 0 ? amount(yq) : "", body, true));
                table.addCell(cell(first && other.signum() != 0 ? amount(other) : "", body, true));
                table.addCell(cell(first ? InvoicePdfRenderer.money(total) : "", body, true));
            }
        }
        return table;
    }

    /** One block per hotel voucher: its guests down the first column, the stay on the first row. */
    private static PdfPTable stays(Invoice invoice, List<InvoiceLineItem> lines, Map<String, String> references,
                                   Map<String, Map<String, String>> details, Font body) {
        PdfPTable table = new PdfPTable(new float[]{2.8f, 2.6f, 1f, 1f, 0.7f, 2.6f, 1.1f, 1.3f});
        table.setWidthPercentage(100);
        table.setSpacingBefore(4);
        for (String h : List.of("Guest", "Hotel", "Check-in", "Check-out", "Nights", "Room", "Conf. No.", "Total")) {
            PdfPCell c = cell(h, body, h.equals("Total") || h.equals("Nights"));
            if (h.equals("Room")) c.setPaddingLeft(8);
            c.setBackgroundColor(BAND);
            table.addCell(c);
        }
        int guest = 0;
        for (InvoiceLineItem line : lines) {
            Map<String, String> d = line.getBookingDocumentId() != null && details.get(line.getBookingDocumentId()) != null
                    ? details.get(line.getBookingDocumentId()) : Map.of();
            String hotel = String.join(", ", java.util.stream.Stream.of(d.get("hotelName"), d.get("hotelCity"))
                    .filter(v -> v != null && !v.isBlank()).toList());
            java.time.LocalDate in = date(d.get("hotelCheckIn"));
            java.time.LocalDate outDate = date(d.get("hotelCheckOut"));
            String nights = in != null && outDate != null && outDate.isAfter(in)
                    ? String.valueOf(java.time.temporal.ChronoUnit.DAYS.between(in, outDate)) : "";
            String rooms = nn(d.get("hotelRooms")).isBlank() ? "" : d.get("hotelRooms") + " x ";
            String room = (rooms + String.join(" · ", java.util.stream.Stream.of(d.get("hotelRoomType"), d.get("hotelBoardBasis"))
                    .filter(v -> v != null && !v.isBlank()).toList())).trim();
            List<VoucherTraveller> travellers = line.getTravellers() != null ? line.getTravellers() : List.of();
            int rows = Math.max(1, travellers.size());
            for (int i = 0; i < rows; i++) {
                boolean first = i == 0;
                String who = i < travellers.size() ? "%03d %s".formatted(++guest, upper(travellers.get(i).getName()))
                        : hotel.isBlank() ? valueOr(line.getDescription()) : upper(invoice.getClientName());
                table.addCell(cell(who, body, false));
                table.addCell(cell(first ? (hotel.isBlank() ? "" : hotel) : "", body, false));
                table.addCell(cell(first && in != null ? in.format(TRAVEL_DATE) : "", body, false));
                table.addCell(cell(first && outDate != null ? outDate.format(TRAVEL_DATE) : "", body, false));
                table.addCell(cell(first ? nights : "", body, true));
                PdfPCell roomCell = cell(first ? room : "", body, false);
                roomCell.setPaddingLeft(8);
                table.addCell(roomCell);
                table.addCell(cell(first ? valueOr(references.get(line.getBookingDocumentId())) : "", body, false));
                table.addCell(cell(first ? InvoicePdfRenderer.money(nz(line.getTaxableValue())) : "", body, true));
            }
        }
        return table;
    }

    private static java.time.LocalDate date(String iso) {
        try {
            return iso == null || iso.isBlank() ? null : java.time.LocalDate.parse(iso.trim().substring(0, Math.min(10, iso.trim().length())));
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- blocks

    private static PdfPTable header(Invoice invoice, Tenant agency, byte[] logo, Font name, Font body) {
        Image img = null;
        if (logo != null) {
            try {
                img = Image.getInstance(logo);
                img.scaleToFit(60, 50);
            } catch (Exception e) {
                // An unreadable logo just isn't printed.
            }
        }
        // The agency's name and address start at the left edge; a logo, when there is one, sits before them.
        PdfPTable t = img != null ? new PdfPTable(new float[]{0.9f, 3.4f, 2.2f}) : new PdfPTable(new float[]{3.4f, 2.2f});
        t.setWidthPercentage(100);
        if (img != null) {
            PdfPCell logoCell = new PdfPCell(img);
            logoCell.setBorder(0);
            t.addCell(logoCell);
        }

        PdfPTable left = new PdfPTable(1);
        String legalName = invoice.getAgencyLegalName() != null ? invoice.getAgencyLegalName()
                : agency != null ? java.util.Objects.requireNonNullElse(agency.getAgencyName(), "") : "-";
        plain(left, legalName.toUpperCase(), name);
        if (invoice.getAgencyAddress() != null) plain(left, invoice.getAgencyAddress(), body);
        plain(left, stateLine(invoice.getAgencyStateCode()), body);
        if (agency != null && agency.getContactPhone() != null) plain(left, "(M) " + agency.getContactPhone(), body);
        String pan = pan(invoice.getAgencyGstin());
        if (pan != null) plain(left, "(PAN) " + pan, body);
        t.addCell(borderless(left));

        PdfPTable right = new PdfPTable(1);
        if (agency != null && agency.getWebsite() != null) plain(right, "(w) " + agency.getWebsite(), body);
        if (agency != null && agency.getContactEmail() != null) plain(right, "(e) " + agency.getContactEmail(), body);
        if (invoice.getAgencyGstin() != null) plain(right, "(GSTIN) " + invoice.getAgencyGstin(), body);
        t.addCell(borderless(right));

        PdfPCell rule = new PdfPCell(new Phrase(" ", body));
        rule.setColspan(img != null ? 3 : 2);
        rule.setBorder(Rectangle.BOTTOM);
        rule.setFixedHeight(10);
        t.addCell(rule);
        return t;
    }

    private static PdfPTable billTo(Invoice invoice, String referenceLabel, List<String> pnrs, Font bold, Font body, Font title, Font pnr) {
        PdfPTable t = new PdfPTable(new float[]{3.4f, 2.2f});
        t.setWidthPercentage(100);
        t.setSpacingBefore(12);
        PdfPTable left = new PdfPTable(1);
        plain(left, "To", body);
        plain(left, valueOr(invoice.getClientName()), bold);
        if (invoice.getBillingAddress() != null && !invoice.getBillingAddress().isBlank()) plain(left, invoice.getBillingAddress(), body);
        if (invoice.getClientStateCode() != null) plain(left, stateLine(invoice.getClientStateCode()), body);
        if (invoice.getClientGstin() != null && !invoice.getClientGstin().isBlank()) plain(left, "(GSTIN) " + invoice.getClientGstin(), body);
        t.addCell(borderless(left));

        PdfPTable box = new PdfPTable(new float[]{1.3f, 1f});
        PdfPCell head = new PdfPCell(new Phrase(documentTitle(invoice), title));
        head.setColspan(2);
        head.setBorder(0);
        head.setHorizontalAlignment(Element.ALIGN_CENTER);
        box.addCell(head);
        PdfPCell number = new PdfPCell(new Phrase(valueOr(invoice.getInvoiceNumber()), title));
        number.setBorder(0);
        box.addCell(number);
        PdfPCell date = new PdfPCell(new Phrase(invoice.getInvoiceDate() != null ? invoice.getInvoiceDate().format(INVOICE_DATE) : "", title));
        date.setBorder(0);
        date.setHorizontalAlignment(Element.ALIGN_RIGHT);
        box.addCell(date);
        if (!pnrs.isEmpty()) {
            PdfPCell ref = new PdfPCell(new Phrase(referenceLabel + " (" + String.join(", ", pnrs) + ")", pnr));
            ref.setColspan(2);
            ref.setBorder(0);
            box.addCell(ref);
        }
        PdfPCell boxCell = new PdfPCell(box);
        boxCell.setBackgroundColor(BAND);
        boxCell.setBorder(0);
        boxCell.setPadding(8);
        t.addCell(boxCell);

        String supply = invoice.getPlaceOfSupplyCode() != null ? invoice.getPlaceOfSupplyCode() : invoice.getClientStateCode();
        if (supply != null) {
            PdfPCell pos = new PdfPCell(new Phrase("Place of Supply: " + stateName(supply) + " (" + supply + ")", body));
            pos.setColspan(2);
            pos.setBorder(Rectangle.BOTTOM);
            pos.setPaddingTop(10);
            pos.setPaddingBottom(6);
            t.addCell(pos);
        }
        return t;
    }

    private static PdfPTable totals(Invoice invoice, List<InvoiceTax> taxes, BigDecimal gross, String base, Tenant agency,
                                    String reimbursement, Font bold, Font body) {
        PdfPTable t = new PdfPTable(new float[]{3.6f, 1.6f, 1.2f});
        t.setWidthPercentage(100);
        PdfPCell reimb = new PdfPCell(new Phrase(reimbursement, bold));
        reimb.setBorder(Rectangle.TOP);
        reimb.setHorizontalAlignment(Element.ALIGN_RIGHT);
        t.addCell(reimb);
        totalRow(t, "Gross Total", InvoicePdfRenderer.money(gross), body, Rectangle.TOP);

        PdfPTable bank = bank(agency, body);
        String foreign = invoice.getCurrencyCode() != null && !base.equals(invoice.getCurrencyCode()) ? invoice.getCurrencyCode() : null;
        List<String[]> rows = new ArrayList<>();
        BigDecimal hidden = BigDecimal.ZERO;
        for (InvoiceTax tax : taxes) {
            if (!Boolean.TRUE.equals(tax.getVisibleToCustomer())) {
                hidden = hidden.add(nz(tax.getAmount()));
                continue;
            }
            if (nz(tax.getAmount()).signum() == 0) continue;
            BigDecimal rate = tax.getRatePercent();
            if (nz(tax.getCgstAmount()).signum() != 0 || nz(tax.getSgstAmount()).signum() != 0) {
                String half = rate != null ? percent(rate.divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP)) : "";
                rows.add(new String[]{"CGST" + (half.isEmpty() ? "" : " " + half), withBase(tax.getCgstAmount(), invoice, foreign, base)});
                rows.add(new String[]{"SGST" + (half.isEmpty() ? "" : " " + half), withBase(tax.getSgstAmount(), invoice, foreign, base)});
            } else if (nz(tax.getIgstAmount()).signum() != 0) {
                rows.add(new String[]{"IGST" + (rate != null ? " " + percent(rate) : ""), withBase(tax.getIgstAmount(), invoice, foreign, base)});
            } else {
                rows.add(new String[]{tax.getLabel(), InvoicePdfRenderer.money(tax.getAmount())});
            }
        }
        if (hidden.signum() != 0) rows.add(new String[]{"Other charges", InvoicePdfRenderer.money(hidden)});
        if (nz(invoice.getRoundOff()).signum() != 0) rows.add(new String[]{"Round Off", InvoicePdfRenderer.money(invoice.getRoundOff())});

        PdfPCell bankCell = bank != null ? new PdfPCell(bank) : new PdfPCell(new Phrase("", body));
        bankCell.setBorder(0);
        bankCell.setRowspan(Math.max(1, rows.size()));
        t.addCell(bankCell);
        if (rows.isEmpty()) {
            totalRow(t, "", "", body, 0);
        }
        for (String[] r : rows) {
            totalRow(t, r[0], r[1], body, 0);
        }

        // An invoice billed in another currency: its own total and words, with the base total beside it.
        BigDecimal grandBase = nz(invoice.getGrandTotalInr());
        if (foreign == null) {
            PdfPCell words = new PdfPCell(new Phrase(AmountInWords.forAmount(grandBase, base) + " Only", bold));
            words.setBorder(Rectangle.BOX);
            t.addCell(words);
            totalRow(t, "Total Amount in " + base, InvoicePdfRenderer.money(grandBase), bold, Rectangle.TOP, true);
        } else {
            t.addCell(blank(body));
            totalRow(t, "Total Amount in " + base, InvoicePdfRenderer.money(grandBase), bold, Rectangle.TOP, true);
            PdfPCell fw = new PdfPCell(new Phrase(AmountInWords.forAmount(invoice.getGrandTotal(), foreign) + " Only", bold));
            fw.setBorder(Rectangle.BOX);
            t.addCell(fw);
            totalRow(t, "Total Amount in " + foreign + " (@ " + rate(invoice.getFxRateToInr()) + ")",
                    InvoicePdfRenderer.money(invoice.getGrandTotal()), bold, 0, true);
        }
        return t;
    }

    private static PdfPTable terms(Invoice invoice, Tenant agency, Font bold, Font small, Font body) {
        PdfPTable t = new PdfPTable(new float[]{4f, 1f});
        t.setWidthPercentage(100);
        t.setSpacingBefore(16);
        PdfPCell head = new PdfPCell(new Phrase("Terms :", bold));
        head.setBorder(0);
        t.addCell(head);
        PdfPCell eoe = new PdfPCell(new Phrase("E. & O. E.", body));
        eoe.setBorder(0);
        eoe.setHorizontalAlignment(Element.ALIGN_RIGHT);
        t.addCell(eoe);
        String terms = invoice.getTerms() != null && !invoice.getTerms().isBlank() ? invoice.getTerms()
                : agency != null ? agency.getInvoiceTerms() : null;
        PdfPCell text = new PdfPCell(new Phrase(terms != null ? terms : "", small));
        text.setBorder(Rectangle.BOTTOM);
        text.setColspan(2);
        text.setPaddingBottom(6);
        t.addCell(text);
        String legalName = invoice.getAgencyLegalName() != null ? invoice.getAgencyLegalName()
                : agency != null ? java.util.Objects.requireNonNullElse(agency.getAgencyName(), "") : "";
        PdfPCell forAgency = new PdfPCell(new Phrase("for " + legalName.toUpperCase(), bold));
        forAgency.setColspan(2);
        forAgency.setBorder(0);
        forAgency.setPaddingTop(4);
        forAgency.setHorizontalAlignment(Element.ALIGN_RIGHT);
        t.addCell(forAgency);
        return t;
    }

    private static PdfPTable footer(Invoice invoice, String preparedBy, Font body, Font small) {
        PdfPTable t = new PdfPTable(new float[]{1f, 2f, 1f});
        t.setWidthPercentage(100);
        t.setSpacingBefore(48);
        PdfPCell receiver = new PdfPCell(new Phrase("Receiver's Signature", body));
        receiver.setBorder(Rectangle.BOTTOM);
        t.addCell(receiver);
        PdfPCell mid = new PdfPCell(new Phrase("", body));
        mid.setBorder(Rectangle.BOTTOM);
        t.addCell(mid);
        PdfPCell signatory = new PdfPCell(new Phrase("Authorized Signatory", body));
        signatory.setBorder(Rectangle.BOTTOM);
        signatory.setHorizontalAlignment(Element.ALIGN_RIGHT);
        t.addCell(signatory);
        PdfPCell prepared = new PdfPCell(new Phrase(preparedBy != null ? "(Prepared by: " + preparedBy + ")" : "", small));
        prepared.setBorder(0);
        t.addCell(prepared);
        PdfPCell note = new PdfPCell(new Phrase("This is a Computer generated document and does not require any signature.", small));
        note.setBorder(0);
        note.setHorizontalAlignment(Element.ALIGN_CENTER);
        t.addCell(note);
        String id = invoice.getId() != null ? invoice.getId().replace("-", "") : "";
        PdfPCell idCell = new PdfPCell(new Phrase("(ID: " + (id.length() > 8 ? id.substring(0, 8) : id).toUpperCase() + ")", small));
        idCell.setBorder(0);
        idCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        t.addCell(idCell);
        return t;
    }

    private static PdfPTable bank(Tenant agency, Font body) {
        if (agency == null || agency.getBankAccountNumber() == null || agency.getBankAccountNumber().isBlank()) {
            return null;
        }
        PdfPTable t = new PdfPTable(1);
        if (agency.getBankAccountName() != null) plain(t, agency.getBankAccountName(), body);
        plain(t, "ACCOUNT NUMBER - " + agency.getBankAccountNumber(), body);
        if (agency.getBankIfscCode() != null) plain(t, "IFSC CODE - " + agency.getBankIfscCode(), body);
        if (agency.getBankBranch() != null) plain(t, "BRANCH - " + agency.getBankBranch(), body);
        return t;
    }

    // ---------------------------------------------------------------- helpers

    /** A tax on an invoice in another currency also shows its base value - GST is returned in base. */
    private static String withBase(BigDecimal amount, Invoice invoice, String foreign, String base) {
        String own = InvoicePdfRenderer.money(amount);
        return foreign == null ? own : own + "  (" + base + " " + InvoicePdfRenderer.money(nz(amount).multiply(invoice.getFxRateToInr())) + ")";
    }

    private static String rate(BigDecimal fx) {
        return fx == null ? "" : fx.stripTrailingZeros().toPlainString();
    }

    private static PdfPCell blank(Font font) {
        PdfPCell c = new PdfPCell(new Phrase("", font));
        c.setBorder(0);
        return c;
    }

    /** "International Air Ticket Invoice" - the category's own document title. */
    private static String documentTitle(Invoice invoice) {
        return invoice.getServiceCategory() != null ? invoice.getServiceCategory().documentTitle() : "Tax Invoice";
    }

    /** The PAN is characters 3-12 of a GSTIN. */
    static String pan(String gstin) {
        return gstin != null && gstin.trim().length() == 15 ? gstin.trim().substring(2, 12).toUpperCase() : null;
    }

    private static String stateLine(String code) {
        if (code == null) return "Country: India.";
        return "State: " + stateName(code) + " (State Code: " + code + "), Country: India.";
    }

    private static String stateName(String code) {
        String name = GstStateCode.nameOf(code);
        return name != null ? name : code;
    }

    static String cabin(String cabin) {
        if (cabin == null || cabin.isBlank()) return "";
        String c = cabin.trim().toUpperCase();
        return CABINS.getOrDefault(c, c.length() > 3 ? c.substring(0, 3) : c);
    }

    private static boolean hasLegs(InvoiceLineItem l) {
        return l.getLegs() != null && !l.getLegs().isEmpty();
    }

    private static String percent(BigDecimal rate) {
        return rate.stripTrailingZeros().toPlainString() + "%";
    }

    /** Whole amounts print as the sample does: 138996, 1400.50. */
    private static String amount(BigDecimal v) {
        BigDecimal s = v.setScale(2, RoundingMode.HALF_UP);
        return s.stripTrailingZeros().scale() <= 0 ? s.setScale(0, RoundingMode.UNNECESSARY).toPlainString() : s.toPlainString();
    }

    private static String upper(String s) {
        return s == null ? "" : s.toUpperCase();
    }

    private static String nn(String s) {
        return s == null ? "" : s;
    }

    private static String valueOr(String s) {
        return s == null ? "" : s;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static PdfPCell cell(String text, Font font, boolean right) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setBorder(0);
        c.setPaddingTop(4);
        c.setPaddingBottom(4);
        if (right) c.setHorizontalAlignment(Element.ALIGN_RIGHT);
        return c;
    }

    private static void plain(PdfPTable table, String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBorder(0);
        cell.setPadding(2);
        table.addCell(cell);
    }

    private static PdfPCell borderless(PdfPTable inner) {
        PdfPCell cell = new PdfPCell(inner);
        cell.setBorder(0);
        return cell;
    }

    private static PdfPTable single(String text, Font font, int border) {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setBorder(border);
        c.setPaddingTop(8);
        c.setPaddingBottom(6);
        t.addCell(c);
        return t;
    }

    private static PdfPTable spacer(float height) {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        PdfPCell c = new PdfPCell(new Phrase(" "));
        c.setBorder(0);
        c.setFixedHeight(height);
        t.addCell(c);
        return t;
    }

    private static void totalRow(PdfPTable t, String label, String value, Font font, int border) {
        totalRow(t, label, value, font, border, false);
    }

    private static void totalRow(PdfPTable t, String label, String value, Font font, int border, boolean shaded) {
        PdfPCell l = new PdfPCell(new Phrase(label, font));
        PdfPCell v = new PdfPCell(new Phrase(value, font));
        l.setBorder(border);
        v.setBorder(border);
        l.setPadding(4);
        v.setPadding(4);
        v.setHorizontalAlignment(Element.ALIGN_RIGHT);
        if (shaded) {
            l.setBackgroundColor(BAND);
            v.setBackgroundColor(BAND);
        }
        t.addCell(l);
        t.addCell(v);
    }
}
