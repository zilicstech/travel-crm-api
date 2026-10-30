package com.voyra.crm.util;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;

/** Reads the text of a rendered PDF back, page by page, so renderer tests can assert on what is printed. */
final class PdfText {

    private PdfText() {
    }

    static String of(byte[] pdf) {
        try {
            PdfReader reader = new PdfReader(pdf);
            PdfTextExtractor ex = new PdfTextExtractor(reader);
            StringBuilder sb = new StringBuilder();
            for (int p = 1; p <= reader.getNumberOfPages(); p++) {
                sb.append(ex.getTextFromPage(p)).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
