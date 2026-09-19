package com.voyra.crm.util;

import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.voyra.crm.entity.PaymentReceipt;
import com.voyra.crm.enums.ReceiptDirection;

import java.io.ByteArrayOutputStream;

/**
 * Renders one append-only {@link PaymentReceipt} row as a voucher PDF - a reversal renders with
 * its own negative amount and a note pointing at the receipt it reverses, exactly as stored;
 * nothing here is ever re-derived.
 */
public final class ReceiptVoucherRenderer {

    private ReceiptVoucherRenderer() {
    }

    public static byte[] write(PaymentReceipt receipt, String invoiceNumber) {
        Document document = new Document(PageSize.A5, 32, 32, 36, 36);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(document, out);
            document.open();

            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14);
            Font labelFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
            Font bodyFont = FontFactory.getFont(FontFactory.HELVETICA, 9);

            String title = receipt.getDirection() == ReceiptDirection.REFUND ? "REFUND VOUCHER" : "PAYMENT RECEIPT";
            document.add(new Paragraph(title, titleFont));
            document.add(new Paragraph(" "));

            PdfPTable table = new PdfPTable(2);
            table.setWidthPercentage(100);
            row(table, "Receipt No.", InvoicePdfRenderer.valueOr(receipt.getReceiptNumber()), labelFont, bodyFont);
            row(table, "Date", InvoicePdfRenderer.str(receipt.getReceivedOn()), labelFont, bodyFont);
            row(table, "Against Invoice", InvoicePdfRenderer.valueOr(invoiceNumber), labelFont, bodyFont);
            row(table, "Amount", InvoicePdfRenderer.money(receipt.getAmount()) + " " + receipt.getCurrencyCode(), labelFont, bodyFont);
            if (!"INR".equals(receipt.getCurrencyCode())) {
                row(table, "Amount (INR)", InvoicePdfRenderer.money(receipt.getAmountInr()), labelFont, bodyFont);
            }
            row(table, "Payment Mode", String.valueOf(receipt.getPaymentMode()), labelFont, bodyFont);
            row(table, "Reference", InvoicePdfRenderer.valueOr(receipt.getInstrumentRef()), labelFont, bodyFont);
            if (receipt.getReversesReceiptId() != null) {
                row(table, "Reverses", receipt.getReversesReceiptId(), labelFont, bodyFont);
            }
            if (receipt.getNotes() != null && !receipt.getNotes().isBlank()) {
                row(table, "Notes", receipt.getNotes(), labelFont, bodyFont);
            }
            document.add(table);
        } finally {
            if (document.isOpen()) {
                document.close();
            }
        }
        return out.toByteArray();
    }

    private static void row(PdfPTable table, String label, String value, Font labelFont, Font bodyFont) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label, labelFont));
        labelCell.setBorder(0);
        PdfPCell valueCell = new PdfPCell(new Phrase(value, bodyFont));
        valueCell.setBorder(0);
        table.addCell(labelCell);
        table.addCell(valueCell);
    }
}
