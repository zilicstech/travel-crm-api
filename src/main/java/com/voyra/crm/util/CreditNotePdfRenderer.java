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
import com.voyra.crm.entity.CreditNote;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;

/**
 * Renders a {@link CreditNote} as a PDF, on demand, from figures already frozen at issue - see
 * {@link InvoicePdfRenderer}'s class javadoc for why this never re-reads a tax rate.
 */
public final class CreditNotePdfRenderer {

    private CreditNotePdfRenderer() {
    }

    public static byte[] write(CreditNote note) {
        Document document = new Document(PageSize.A4, 32, 32, 36, 36);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(document, out);
            document.open();

            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
            Font labelFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
            Font bodyFont = FontFactory.getFont(FontFactory.HELVETICA, 9);

            document.add(new Paragraph("CREDIT NOTE", titleFont));
            document.add(new Paragraph(" "));

            PdfPTable meta = new PdfPTable(2);
            meta.setWidthPercentage(100);
            addRow(meta, "Credit Note No.", InvoicePdfRenderer.valueOr(note.getCreditNoteNumber()), labelFont, bodyFont);
            addRow(meta, "Date", InvoicePdfRenderer.str(note.getNoteDate()), labelFont, bodyFont);
            addRow(meta, "Against Invoice", InvoicePdfRenderer.valueOr(note.getInvoiceNumber()), labelFont, bodyFont);
            addRow(meta, "Client", note.getClientName(), labelFont, bodyFont);
            addRow(meta, "Reason", note.getReason() + (note.getReasonNote() != null ? " - " + note.getReasonNote() : ""), labelFont, bodyFont);
            document.add(meta);
            document.add(new Paragraph(" "));

            PdfPTable totals = new PdfPTable(new float[]{3f, 1.5f});
            totals.setWidthPercentage(50);
            totals.setHorizontalAlignment(Element.ALIGN_RIGHT);
            totalRow(totals, "Taxable Value Reversed", InvoicePdfRenderer.money(note.getTaxableValue()), bodyFont);
            if (note.getIgstAmount().compareTo(BigDecimal.ZERO) > 0) {
                totalRow(totals, "IGST Reversed", InvoicePdfRenderer.money(note.getIgstAmount()), bodyFont);
            } else {
                totalRow(totals, "CGST Reversed", InvoicePdfRenderer.money(note.getCgstAmount()), bodyFont);
                totalRow(totals, "SGST Reversed", InvoicePdfRenderer.money(note.getSgstAmount()), bodyFont);
            }
            if (note.getTcsAmount().compareTo(BigDecimal.ZERO) > 0) {
                totalRow(totals, "TCS Reversed", InvoicePdfRenderer.money(note.getTcsAmount()), bodyFont);
            }
            if (note.getCancellationFee().compareTo(BigDecimal.ZERO) > 0) {
                totalRow(totals, "Retained (still taxable)", InvoicePdfRenderer.money(note.getCancellationFee()), bodyFont);
            }
            totalRow(totals, "Total Amount", InvoicePdfRenderer.money(note.getTotalAmount()) + " " + note.getCurrencyCode(), labelFont);
            totalRow(totals, "Refundable", InvoicePdfRenderer.money(note.getRefundableAmount()), bodyFont);
            totalRow(totals, "Refunded So Far", InvoicePdfRenderer.money(note.getRefundedAmount()), bodyFont);
            document.add(totals);
        } finally {
            if (document.isOpen()) {
                document.close();
            }
        }
        return out.toByteArray();
    }

    private static void addRow(PdfPTable table, String label, String value, Font labelFont, Font bodyFont) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label, labelFont));
        labelCell.setBorder(0);
        PdfPCell valueCell = new PdfPCell(new Phrase(value, bodyFont));
        valueCell.setBorder(0);
        table.addCell(labelCell);
        table.addCell(valueCell);
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
}
