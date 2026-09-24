package com.voyra.crm.util;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.entity.InvoiceLineItem;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.enums.InvoiceServiceCategory;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Renders a {@link Invoice} - proforma or tax invoice - as a GST-compliant PDF, on demand, every
 * time it is requested. There is no cached copy: every figure this reads is already frozen on
 * {@link Invoice} and {@link InvoiceLineItem} at issue (see {@code InvoiceDocumentService}), and
 * {@link com.voyra.crm.entity.TaxRateConfig} is never consulted here - deleting every
 * {@code tax_rate_config} row a year later reproduces byte-for-byte the same PDF, because nothing
 * here ever re-reads a rate; it only re-formats what was already computed and saved.
 */
public final class InvoicePdfRenderer {

    private InvoicePdfRenderer() {
    }

    /** @deprecated kept only for any caller that hasn't been updated to pass agency/booking. */
    public static byte[] write(Invoice invoice, List<InvoiceLineItem> lines) {
        return write(invoice, lines, null, null);
    }

    /**
     * {@code agency} supplies the bank block (Tenant.bank* - never frozen on the invoice, read
     * fresh every print, same as the logo). {@code booking}, when supplied, supplies the one
     * category-specific reference line under the title (PNR, hotel confirmation number, ...) -
     * see ACCOUNTING_REDESIGN_SPEC.md §3. Both are nullable so a pre-redesign invoice with no
     * {@code serviceCategory}, or one whose booking has since been deleted, still prints.
     */
    public static byte[] write(Invoice invoice, List<InvoiceLineItem> lines, Tenant agency, Booking booking) {
        Document document = new Document(PageSize.A4, 32, 32, 36, 36);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(document, out);
            document.open();

            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
            Font subtitleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLDOBLIQUE, 10);
            Font labelFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
            Font bodyFont = FontFactory.getFont(FontFactory.HELVETICA, 9);
            Font smallFont = FontFactory.getFont(FontFactory.HELVETICA, 8);

            document.add(new Paragraph(docTitle(invoice), titleFont));
            String reference = keyReference(invoice.getServiceCategory(), booking);
            if (reference != null) {
                document.add(new Paragraph(reference, subtitleFont));
            }
            document.add(new Paragraph(" "));

            PdfPTable header = new PdfPTable(2);
            header.setWidthPercentage(100);
            header.setWidths(new float[]{1f, 1f});
            header.addCell(borderless(supplierBlock(invoice, labelFont, bodyFont)));
            header.addCell(borderless(documentMetaBlock(invoice, labelFont, bodyFont)));
            document.add(header);
            document.add(new Paragraph(" "));

            document.add(billToBlock(invoice, labelFont, bodyFont));
            document.add(new Paragraph(" "));

            document.add(lineItemsTable(lines, labelFont, smallFont));
            document.add(new Paragraph(" "));

            document.add(totalsTable(invoice, labelFont, bodyFont));
            document.add(new Paragraph(" "));
            document.add(new Paragraph(
                    AmountInWords.forAmount(invoice.getGrandTotal(), invoice.getCurrencyCode()) + " Only",
                    FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9)));

            PdfPTable bank = bankBlock(agency, labelFont, bodyFont);
            if (bank != null) {
                document.add(new Paragraph(" "));
                document.add(bank);
            }

            if (invoice.getNotes() != null && !invoice.getNotes().isBlank()) {
                document.add(new Paragraph(" "));
                document.add(new Paragraph("Notes: " + invoice.getNotes(), smallFont));
            }
            if (invoice.getTerms() != null && !invoice.getTerms().isBlank()) {
                document.add(new Paragraph("Terms: " + invoice.getTerms(), smallFont));
            }
        } finally {
            if (document.isOpen()) {
                document.close();
            }
        }
        return out.toByteArray();
    }

    private static String docTitle(Invoice invoice) {
        if (invoice.getDocumentType() == com.voyra.crm.enums.InvoiceDocumentType.PROFORMA) {
            return "PAYMENT REQUEST";
        }
        return invoice.getServiceCategory() != null
                ? invoice.getServiceCategory().documentTitle().toUpperCase()
                : "TAX INVOICE";
    }

    /** The one identifying reference the category's own document is built around - see
     *  ACCOUNTING_REDESIGN_SPEC.md §3 ("Airline PNR (LVBJAY)"). */
    private static String keyReference(InvoiceServiceCategory category, Booking booking) {
        if (category == null || booking == null) {
            return null;
        }
        return switch (category) {
            case AIR_INTERNATIONAL, AIR_DOMESTIC, RAIL ->
                    booking.getPnr() != null ? "PNR: " + booking.getPnr() : null;
            case HOTEL ->
                    booking.getHotelConfirmationNo() != null ? "Confirmation No: " + booking.getHotelConfirmationNo() : null;
            case VISA ->
                    booking.getVisaApplicationNo() != null ? "Application No: " + booking.getVisaApplicationNo() : null;
            case TRANSPORT ->
                    booking.getTransferVoucherNo() != null ? "Voucher No: " + booking.getTransferVoucherNo() : null;
            case PACKAGE, MISCELLANEOUS -> null;
        };
    }

    private static PdfPTable bankBlock(Tenant agency, Font labelFont, Font bodyFont) {
        if (agency == null || isBlank(agency.getBankAccountNumber())) {
            return null;
        }
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);
        addPlain(table, "Bank Details", labelFont);
        if (!isBlank(agency.getBankAccountName())) {
            addPlain(table, agency.getBankAccountName(), bodyFont);
        }
        addPlain(table, "Account Number: " + agency.getBankAccountNumber(), bodyFont);
        if (!isBlank(agency.getBankIfscCode())) {
            addPlain(table, "IFSC: " + agency.getBankIfscCode(), bodyFont);
        }
        if (!isBlank(agency.getBankBranch())) {
            addPlain(table, "Branch: " + agency.getBankBranch(), bodyFont);
        }
        return table;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static PdfPTable supplierBlock(Invoice invoice, Font labelFont, Font bodyFont) {
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);
        addPlain(table, invoice.getAgencyLegalName() != null ? invoice.getAgencyLegalName() : "-", labelFont);
        addPlain(table, invoice.getAgencyAddress() != null ? invoice.getAgencyAddress() : "-", bodyFont);
        addPlain(table, "GSTIN: " + valueOr(invoice.getAgencyGstin()), bodyFont);
        addPlain(table, "State: " + valueOr(invoice.getAgencyStateCode()), bodyFont);
        return table;
    }

    private static PdfPTable documentMetaBlock(Invoice invoice, Font labelFont, Font bodyFont) {
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);
        addRightAligned(table, "No: " + valueOr(invoice.getInvoiceNumber()), labelFont);
        addRightAligned(table, "Date: " + valueOr(str(invoice.getInvoiceDate())), bodyFont);
        addRightAligned(table, "Due: " + valueOr(str(invoice.getDueDate())), bodyFont);
        addRightAligned(table, "Currency: " + invoice.getCurrencyCode()
                + (!"INR".equals(invoice.getCurrencyCode()) ? " (locked @ " + invoice.getFxRateToInr() + ")" : ""), bodyFont);
        return table;
    }

    private static PdfPTable billToBlock(Invoice invoice, Font labelFont, Font bodyFont) {
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);
        addPlain(table, "Bill To", labelFont);
        addPlain(table, invoice.getClientName(), bodyFont);
        if (invoice.getBillingAddress() != null && !invoice.getBillingAddress().isBlank()) {
            addPlain(table, invoice.getBillingAddress(), bodyFont);
        }
        addPlain(table, "GSTIN: " + valueOr(invoice.getClientGstin()) + "   Place of Supply: " + valueOr(invoice.getPlaceOfSupplyCode())
                + "   Treatment: " + invoice.getTaxTreatment(), bodyFont);
        return table;
    }

    private static PdfPTable lineItemsTable(List<InvoiceLineItem> lines, Font headerFont, Font cellFont) {
        PdfPTable table = new PdfPTable(new float[]{3f, 1.2f, 1f, 1.2f, 1.2f, 1.5f});
        table.setWidthPercentage(100);
        for (String h : List.of("Description", "SAC", "Qty", "Unit Price", "Taxable Value", "Line Total")) {
            table.addCell(headerCell(h, headerFont));
        }
        for (InvoiceLineItem line : lines) {
            table.addCell(new PdfPCell(new Phrase(line.getDescription(), cellFont)));
            table.addCell(new PdfPCell(new Phrase(valueOr(line.getSacCode()), cellFont)));
            rightCell(table, line.getQuantity().stripTrailingZeros().toPlainString(), cellFont);
            rightCell(table, money(line.getUnitPrice()), cellFont);
            rightCell(table, money(line.getTaxableValue()), cellFont);
            rightCell(table, money(line.getLineTotal()), cellFont);
        }
        return table;
    }

    private static PdfPTable totalsTable(Invoice invoice, Font labelFont, Font bodyFont) {
        PdfPTable table = new PdfPTable(new float[]{3f, 1.5f});
        table.setWidthPercentage(50);
        table.setHorizontalAlignment(Element.ALIGN_RIGHT);

        totalRow(table, "Taxable Value", money(invoice.getTaxableValue()), bodyFont);
        if (invoice.getIgstAmount().compareTo(BigDecimal.ZERO) > 0) {
            totalRow(table, "IGST", money(invoice.getIgstAmount()), bodyFont);
        } else {
            totalRow(table, "CGST", money(invoice.getCgstAmount()), bodyFont);
            totalRow(table, "SGST", money(invoice.getSgstAmount()), bodyFont);
        }
        if (invoice.getTcsAmount().compareTo(BigDecimal.ZERO) > 0) {
            totalRow(table, "TCS (" + valueOr(invoice.getTcsSection()) + ")", money(invoice.getTcsAmount()), bodyFont);
        }
        if (invoice.getRoundOff().compareTo(BigDecimal.ZERO) != 0) {
            totalRow(table, "Round Off", money(invoice.getRoundOff()), bodyFont);
        }
        // Matches the source system's own dual-currency block (spec §2.5/§3): INR total
        // always shown, the invoice's own currency shown alongside it when that isn't INR.
        totalRow(table, "Total Amount in ₹", money(invoice.getGrandTotalInr()), labelFont);
        if (!"INR".equals(invoice.getCurrencyCode())) {
            totalRow(table, "Total Amount in " + invoice.getCurrencyCode(), money(invoice.getGrandTotal()), labelFont);
        }
        return table;
    }

    // ---------------------------------------------------------------- small helpers

    private static void addPlain(PdfPTable table, String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBorder(0);
        table.addCell(cell);
    }

    private static void addRightAligned(PdfPTable table, String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBorder(0);
        cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.addCell(cell);
    }

    private static PdfPCell borderless(PdfPTable inner) {
        PdfPCell cell = new PdfPCell(inner);
        cell.setBorder(0);
        return cell;
    }

    private static PdfPCell headerCell(String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setGrayFill(0.9f);
        return cell;
    }

    private static void rightCell(PdfPTable table, String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.addCell(cell);
    }

    private static void totalRow(PdfPTable table, String label, String value, Font font) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label, font));
        labelCell.setBorder(0);
        labelCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        PdfPCell valueCell = new PdfPCell(new Phrase(value, font));
        valueCell.setBorder(0);
        valueCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.addCell(labelCell);
        table.addCell(valueCell);
    }

    static String money(BigDecimal amount) {
        return amount == null ? "0.00" : amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    static String valueOr(String value) {
        return value != null && !value.isBlank() ? value : "-";
    }

    static String str(Object value) {
        return value != null ? value.toString() : "-";
    }
}
