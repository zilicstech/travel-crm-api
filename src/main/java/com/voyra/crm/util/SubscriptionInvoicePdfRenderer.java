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
import com.voyra.crm.entity.SubscriptionInvoice;
import com.voyra.crm.entity.Tenant;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/** Voyra's subscription invoice to an agency, from the figures frozen on the invoice at issue (same OpenPDF style as CreditNotePdfRenderer). */
public final class SubscriptionInvoicePdfRenderer {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)
            .withZone(ZoneId.of("Asia/Kolkata"));

    private SubscriptionInvoicePdfRenderer() {
    }

    /** {@code agency} may be null (deleted tenant): the bill-to block then shows only the tenant id. */
    public static byte[] write(SubscriptionInvoice inv, Tenant agency) {
        Document document = new Document(PageSize.A4, 32, 32, 36, 36);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(document, out);
            document.open();

            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
            Font labelFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
            Font bodyFont = FontFactory.getFont(FontFactory.HELVETICA, 9);

            document.add(new Paragraph("TAX INVOICE", titleFont));
            document.add(new Paragraph("Voyra - CRM subscription", bodyFont));
            document.add(new Paragraph(" "));

            PdfPTable meta = new PdfPTable(2);
            meta.setWidthPercentage(100);
            row(meta, "Invoice No.", InvoicePdfRenderer.valueOr(inv.getNumber()), labelFont, bodyFont);
            row(meta, "Invoice Date", date(inv.getCreatedAt()), labelFont, bodyFont);
            row(meta, "Period", date(inv.getPeriodStart()) + " - " + date(inv.getPeriodEnd()), labelFont, bodyFont);
            row(meta, "Due Date", date(inv.getDueAt()), labelFont, bodyFont);
            row(meta, "Billed To", agency == null ? inv.getTenantId()
                    : agency.getLegalName() != null ? agency.getLegalName() : agency.getAgencyName(), labelFont, bodyFont);
            if (agency != null && agency.getGstNumber() != null) {
                row(meta, "GSTIN", agency.getGstNumber(), labelFont, bodyFont);
            }
            if (agency != null && agency.getAddress() != null) {
                row(meta, "Address", agency.getAddress(), labelFont, bodyFont);
            }
            if ("PAID".equals(inv.getStatus())) {
                row(meta, "Status", "Paid on " + date(inv.getPaidAt()), labelFont, bodyFont);
            } else if ("VOID".equals(inv.getStatus())) {
                row(meta, "Status", "Void", labelFont, bodyFont);
            }
            document.add(meta);
            document.add(new Paragraph(" "));

            PdfPTable items = new PdfPTable(new float[]{4f, 1f, 1.5f, 1f, 1.5f});
            items.setWidthPercentage(100);
            for (String h : new String[]{"Description", "Qty", "Monthly Price", "Months", "Amount"}) {
                PdfPCell cell = new PdfPCell(new Phrase(h, labelFont));
                cell.setGrayFill(0.9f);
                items.addCell(cell);
            }
            for (Map<String, Object> line : inv.getLines()) {
                items.addCell(new Phrase(String.valueOf(line.get("description")), bodyFont));
                items.addCell(right(String.valueOf(line.get("quantity")), bodyFont));
                items.addCell(right(money(line.get("unitPrice")), bodyFont));
                items.addCell(right(String.valueOf(line.get("months")), bodyFont));
                items.addCell(right(money(line.get("amount")), bodyFont));
            }
            document.add(items);
            document.add(new Paragraph(" "));

            PdfPTable totals = new PdfPTable(new float[]{3f, 1.5f});
            totals.setWidthPercentage(50);
            totals.setHorizontalAlignment(Element.ALIGN_RIGHT);
            total(totals, "Taxable Value", InvoicePdfRenderer.money(inv.getAmount()), bodyFont);
            total(totals, "GST @ 18%", InvoicePdfRenderer.money(inv.getGst()), bodyFont);
            total(totals, "Total (INR)", InvoicePdfRenderer.money(inv.getTotal()), labelFont);
            document.add(totals);
            document.add(new Paragraph(" "));
            document.add(new Paragraph(AmountInWords.forAmount(inv.getTotal(), "INR") + " Only", labelFont));
        } finally {
            if (document.isOpen()) {
                document.close();
            }
        }
        return out.toByteArray();
    }

    private static String date(java.time.Instant value) {
        return value == null ? "-" : DATE.format(value);
    }

    private static String money(Object value) {
        return value == null ? "0.00" : InvoicePdfRenderer.money(new BigDecimal(String.valueOf(value)));
    }

    private static PdfPCell right(String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        return cell;
    }

    private static void row(PdfPTable table, String label, String value, Font labelFont, Font bodyFont) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label, labelFont));
        labelCell.setBorder(0);
        PdfPCell valueCell = new PdfPCell(new Phrase(value, bodyFont));
        valueCell.setBorder(0);
        table.addCell(labelCell);
        table.addCell(valueCell);
    }

    private static void total(PdfPTable table, String label, String value, Font font) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label, font));
        labelCell.setBorder(0);
        labelCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        PdfPCell valueCell = new PdfPCell(new Phrase(value, font));
        valueCell.setBorder(0);
        valueCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.addCell(labelCell);
        table.addCell(valueCell);
    }
}
