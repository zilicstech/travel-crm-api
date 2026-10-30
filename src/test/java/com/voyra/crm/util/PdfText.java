package com.voyra.crm.util;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;

/** Reads the text of a rendered PDF back, page by page, so renderer tests can assert on what is printed. */
final class PdfText {

    private PdfText() {
    }

    /** The text with every whitespace removed, for asserting on table rows whose cell spacing the extractor varies. */
    static String flat(byte[] pdf) {
        return flat(of(pdf));
    }

    static String flat(String text) {
        return text.replaceAll("\\s+", "");
    }

    static String[] flat(String... parts) {
        return java.util.Arrays.stream(parts).map(PdfText::flat).toArray(String[]::new);
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
