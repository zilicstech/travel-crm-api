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
import com.voyra.crm.entity.SupplierInvoice;
import com.voyra.crm.entity.SupplierPayment;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.entity.Vendor;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Renders a {@link SupplierPayment} as a printable Payment Advice (FRD US-ACC-3.2: "a printable
 * and emailable Payment Advice PDF detailing the gross bill, prepayments deducted, and net
 * settled amount"). Rendered on demand from figures already saved on the payment and its bill -
 * nothing here recomputes a ledger balance.
 *
 * <p>{@code invoice} and {@code invoicePayments} are null/empty for a pure advance payment (no
 * bill yet) - the advice then reads as a deposit receipt instead of a settlement summary.
 */
public final class PaymentAdvicePdfRenderer {

    private PaymentAdvicePdfRenderer() {
    }

    public static byte[] write(SupplierPayment payment, SupplierInvoice invoice,
                                List<SupplierPayment> invoicePayments, Vendor vendor, Tenant agency) {
        Document document = new Document(PageSize.A4, 32, 32, 36, 36);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(document, out);
            document.open();

            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
            Font labelFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
            Font bodyFont = FontFactory.getFont(FontFactory.HELVETICA, 9);
            Font smallFont = FontFactory.getFont(FontFactory.HELVETICA, 8);

            document.add(new Paragraph("PAYMENT ADVICE", titleFont));
            document.add(new Paragraph(" "));

            PdfPTable header = new PdfPTable(2);
            header.setWidthPercentage(100);
            header.setWidths(new float[]{1f, 1f});
            header.addCell(borderless(vendorBlock(vendor, labelFont, bodyFont)));
            header.addCell(borderless(metaBlock(payment, labelFont, bodyFont)));
            document.add(header);
            document.add(new Paragraph(" "));

            document.add(settlementTable(payment, invoice, invoicePayments, labelFont, bodyFont));
            document.add(new Paragraph(" "));
            document.add(new Paragraph(
                    AmountInWords.forAmount(payment.getAmount().abs(), payment.getCurrencyCode()) + " Only",
                    FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9)));

            PdfPTable bank = agencyBankBlock(agency, labelFont, bodyFont);
            if (bank != null) {
                document.add(new Paragraph(" "));
                document.add(bank);
            }

            if (payment.getNotes() != null && !payment.getNotes().isBlank()) {
                document.add(new Paragraph(" "));
                document.add(new Paragraph("Notes: " + payment.getNotes(), smallFont));
            }
        } finally {
            if (document.isOpen()) {
                document.close();
            }
        }
        return out.toByteArray();
    }

    private static PdfPTable vendorBlock(Vendor vendor, Font labelFont, Font bodyFont) {
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);
        addPlain(table, "Paid To", labelFont);
        addPlain(table, vendor != null ? vendor.getName() : "-", bodyFont);
        if (vendor != null && !isBlank(vendor.getBankAccountNumber())) {
            addPlain(table, "A/C: " + vendor.getBankAccountNumber()
                    + (!isBlank(vendor.getBankIfsc()) ? "  IFSC: " + vendor.getBankIfsc() : ""), bodyFont);
        }
        return table;
    }

    private static PdfPTable metaBlock(SupplierPayment payment, Font labelFont, Font bodyFont) {
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);
        addRightAligned(table, "Voucher: " + valueOr(payment.getVoucherNumber()), labelFont);
        addRightAligned(table, "Date: " + str(payment.getPaidOn()), bodyFont);
        addRightAligned(table, "Mode: " + str(payment.getPaymentMode()), bodyFont);
        addRightAligned(table, "Currency: " + payment.getCurrencyCode()
                + (!"INR".equals(payment.getCurrencyCode()) ? " (@ " + payment.getFxRateToInr() + ")" : ""), bodyFont);
        return table;
    }

    /**
     * No bill means a pure advance - the only row is the deposit itself. A bill present shows
     * the gross bill, every prepayment already applied against it (not counting this voucher,
     * when this voucher is itself one of them), and this voucher's own net settled amount.
     */
    private static PdfPTable settlementTable(SupplierPayment payment, SupplierInvoice invoice,
                                              List<SupplierPayment> invoicePayments, Font labelFont, Font bodyFont) {
        PdfPTable table = new PdfPTable(new float[]{3f, 1.5f});
        table.setWidthPercentage(60);

        if (invoice == null) {
            row(table, "Advance / Deposit", money(payment.getAmount().abs()), labelFont, bodyFont);
            row(table, "Net Settled Amount", money(payment.getAmount().abs()), labelFont, labelFont);
            return table;
        }

        BigDecimal prepaymentsDeducted = (invoicePayments == null ? List.<SupplierPayment>of() : invoicePayments).stream()
                .filter(p -> Boolean.TRUE.equals(p.getAppliedFromAdvance()) && p.getReversedAt() == null
                        && !p.getId().equals(payment.getId()))
                .map(SupplierPayment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        row(table, "Bill Reference", valueOr(invoice.getSupplierInvoiceNumber()), labelFont, bodyFont);
        row(table, "Gross Bill Amount", money(invoice.getGrandTotal()), labelFont, bodyFont);
        if (prepaymentsDeducted.compareTo(BigDecimal.ZERO) != 0) {
            row(table, "Prepayments Deducted", "(" + money(prepaymentsDeducted) + ")", labelFont, bodyFont);
        }
        row(table, "Net Settled Amount (this voucher)", money(payment.getAmount().abs()), labelFont, labelFont);
        row(table, "Balance Due After This Payment", money(invoice.getBalanceDue()), labelFont, bodyFont);
        return table;
    }

    private static PdfPTable agencyBankBlock(Tenant agency, Font labelFont, Font bodyFont) {
        if (agency == null || isBlank(agency.getBankAccountNumber())) {
            return null;
        }
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);
        addPlain(table, "Paid From", labelFont);
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

    // ---------------------------------------------------------------- small helpers

    private static void row(PdfPTable table, String label, String value, Font labelFont, Font valueFont) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label, labelFont));
        labelCell.setBorder(0);
        PdfPCell valueCell = new PdfPCell(new Phrase(value, valueFont));
        valueCell.setBorder(0);
        valueCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.addCell(labelCell);
        table.addCell(valueCell);
    }

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

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String money(BigDecimal amount) {
        return amount == null ? "0.00" : amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String valueOr(String value) {
        return value != null && !value.isBlank() ? value : "-";
    }

    private static String str(Object value) {
        return value != null ? value.toString() : "-";
    }
}
