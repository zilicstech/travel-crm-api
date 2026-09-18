package com.voyra.crm.util;

import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import java.io.ByteArrayOutputStream;

/** Same {@link ReportTable} every export starts from, written as a single-table, landscape .pdf. */
public final class PdfTableWriter {

    private PdfTableWriter() {
    }

    public static byte[] write(String title, ReportTable table) {
        Document document = new Document(PageSize.A4.rotate(), 24, 24, 36, 24);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(document, out);
            document.open();

            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14);
            document.add(new Paragraph(title, titleFont));
            document.add(new Paragraph(" "));

            int columnCount = Math.max(1, table.header().size());
            PdfPTable pdfTable = new PdfPTable(columnCount);
            pdfTable.setWidthPercentage(100);

            Font headerFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
            for (String h : table.header()) {
                PdfPCell cell = new PdfPCell(new com.lowagie.text.Phrase(h, headerFont));
                cell.setGrayFill(0.9f);
                pdfTable.addCell(cell);
            }

            Font cellFont = FontFactory.getFont(FontFactory.HELVETICA, 8);
            for (var row : table.rows()) {
                for (String value : row) {
                    pdfTable.addCell(new PdfPCell(new com.lowagie.text.Phrase(value != null ? value : "", cellFont)));
                }
            }

            document.add(pdfTable);
        } finally {
            if (document.isOpen()) {
                document.close();
            }
        }
        return out.toByteArray();
    }
}
