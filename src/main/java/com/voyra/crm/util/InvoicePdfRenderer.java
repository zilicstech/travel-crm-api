package com.voyra.crm.util;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.BookingCostComponent;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.entity.InvoiceLineItem;
import com.voyra.crm.entity.InvoiceTax;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.enums.InvoiceBillingModel;
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

    /** @deprecated kept only for any caller that hasn't been updated to pass taxes/agency/booking. */
    public static byte[] write(Invoice invoice, List<InvoiceLineItem> lines) {
        return write(invoice, lines, List.of(), null, null, List.of());
    }

    /**
     * {@code agency} supplies the bank block (Tenant.bank* - never frozen on the invoice, read
     * fresh every print, same as the logo). {@code booking}, when supplied, supplies the one
     * category-specific reference line under the title (PNR, hotel confirmation number, ...) -
     * see ACCOUNTING_REDESIGN_SPEC.md §3. Both are nullable so a pre-redesign invoice with no
     * {@code serviceCategory}, or one whose booking has since been deleted, still prints.
     * {@code taxes} whose {@code visibleToCustomer} is false are never printed as their own
     * line - their amount is folded into the printed fare instead, so the grand total the
     * customer sees always matches {@link Invoice#getGrandTotal()} even though the breakup
     * doesn't show every tax that was actually charged. {@code costComponents} feeds the
     * pass-through disbursement block on a {@link InvoiceBillingModel#COMMISSION_AGENT} invoice
     * only (Decision 8) - empty for every other invoice, including every PRINCIPAL one, whose
     * rendering is unchanged from before this parameter existed.
     */
    public static byte[] write(Invoice invoice, List<InvoiceLineItem> lines, List<InvoiceTax> taxes, Tenant agency,
                                Booking booking, List<BookingCostComponent> costComponents) {
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

            if (invoice.getBillingModel() == InvoiceBillingModel.COMMISSION_AGENT) {
                document.add(passThroughBlock(invoice, costComponents, labelFont, bodyFont, smallFont));
                document.add(new Paragraph(" "));
            }

            document.add(totalsTable(invoice, taxes, labelFont, bodyFont));
            document.add(new Paragraph(" "));
            document.add(new Paragraph(
                    AmountInWords.forAmount(invoice.getGrandTotal(), invoice.getCurrencyCode()) + " Only",
                    FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9)));

            PdfPTable bank = bankBlock(agency, labelFont, bodyFont);
            if (bank != null) {
                document.add(new Paragraph(" "));
                PdfPTable bankRow = new PdfPTable(2);
                bankRow.setWidthPercentage(100);
                bankRow.setWidths(new float[]{2.4f, 1f});
                bankRow.addCell(borderless(bank));
                PdfPCell qrCell = new PdfPCell(qrBlock(agency, invoice, labelFont, smallFont));
                qrCell.setBorder(0);
                qrCell.setHorizontalAlignment(Element.ALIGN_CENTER);
                bankRow.addCell(qrCell);
                document.add(bankRow);
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

    /**
     * A scan-to-pay QR next to the bank block (ACCOUNTING_EXPANSION epic A, FRD US-ACC-2.1's
     * "interactive payment link or dynamic QR code"). The agency has no stored UPI VPA today, so
     * the payload is the same bank-transfer details already printed as text, plus the amount and
     * invoice number - a scanning app can't auto-pay from this, but it removes the hand-typing a
     * customer would otherwise do from the printed account number and IFSC.
     */
    private static PdfPTable qrBlock(Tenant agency, Invoice invoice, Font labelFont, Font smallFont) {
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);
        String payload = "Pay to: " + valueOr(agency.getBankAccountName())
                + "\nA/C: " + valueOr(agency.getBankAccountNumber())
                + (!isBlank(agency.getBankIfscCode()) ? "\nIFSC: " + agency.getBankIfscCode() : "")
                + "\nAmount: " + money(invoice.getGrandTotalInr()) + " INR"
                + "\nRef: " + valueOr(invoice.getInvoiceNumber());
        Image qr = QrCodeRenderer.render(payload, 160);
        qr.scaleToFit(80, 80);
        PdfPCell imgCell = new PdfPCell(qr);
        imgCell.setBorder(0);
        imgCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        table.addCell(imgCell);
        PdfPCell caption = new PdfPCell(new Phrase("Scan for bank details", smallFont));
        caption.setBorder(0);
        caption.setHorizontalAlignment(Element.ALIGN_CENTER);
        table.addCell(caption);
        return table;
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
        // GST fields print only when a GST-kind tax was actually charged - most invoices
        // now carry none, and a blank line here would read as a mistake rather than a choice.
        if (invoice.getPlaceOfSupplyCode() != null) {
            addPlain(table, "GSTIN: " + valueOr(invoice.getClientGstin()) + "   Place of Supply: " + valueOr(invoice.getPlaceOfSupplyCode())
                    + "   Treatment: " + str(invoice.getTaxTreatment()), bodyFont);
        }
        return table;
    }

    /**
     * A booking captured with a passenger fare/tax split (see BookingPassenger.taxAmount)
     * prints Fare and Taxes as their own columns instead of one blended amount - "Fare plus
     * taxes," the middle ground between a single figure and the legacy system's full
     * Basic/YQ/YR/K3/OC breakdown. Every line on one invoice is built the same way (either all
     * passenger lines or one whole-booking fallback - see BookingInvoiceLineBuilder), so
     * checking the first line decides the column set for the whole table.
     */
    private static PdfPTable lineItemsTable(List<InvoiceLineItem> lines, Font headerFont, Font cellFont) {
        boolean fareSplit = !lines.isEmpty() && lines.get(0).getFareAmount() != null;
        PdfPTable table = fareSplit
                ? new PdfPTable(new float[]{3.4f, 1f, 1f, 1.3f, 1.3f, 1.3f})
                : new PdfPTable(new float[]{3.4f, 1f, 1f, 1.6f});
        table.setWidthPercentage(100);
        List<String> headers = fareSplit
                ? List.of("Description", "SAC", "Qty", "Fare", "Taxes", "Amount")
                : List.of("Description", "SAC", "Qty", "Amount");
        for (String h : headers) {
            table.addCell(headerCell(h, headerFont));
        }
        for (InvoiceLineItem line : lines) {
            table.addCell(new PdfPCell(new Phrase(line.getDescription(), cellFont)));
            table.addCell(new PdfPCell(new Phrase(valueOr(line.getSacCode()), cellFont)));
            rightCell(table, line.getQuantity().stripTrailingZeros().toPlainString(), cellFont);
            if (fareSplit) {
                rightCell(table, money(line.getFareAmount()), cellFont);
                rightCell(table, money(line.getTaxAmount()), cellFont);
            }
            rightCell(table, money(line.getLineTotal()), cellFont);
        }
        return table;
    }

    /**
     * The disbursement block for a {@link InvoiceBillingModel#COMMISSION_AGENT} invoice -
     * reproduces the client's own "(Reimbursement of air ticket issued by airlines)" framing
     * (ACCOUNTING_REDESIGN_SPEC.md §3/§5.4). Lists what the agency paid out on the traveller's
     * behalf (the booking's cost components, one row per vendor component, or the booking's net
     * cost as a single row when no components were ever captured), then the agency's own service
     * fee - the same fee {@code InvoiceDocumentService#resolveTaxableBase} isolated as the GST
     * base. Purely presentational: it never changes {@link Invoice#getTaxableValue()} or any
     * stored total, it only explains, on paper, why GST was charged on a smaller figure than the
     * package amount shown above.
     */
    private static PdfPTable passThroughBlock(Invoice invoice, List<BookingCostComponent> costComponents,
                                                Font labelFont, Font bodyFont, Font smallFont) {
        PdfPTable table = new PdfPTable(new float[]{3.4f, 1.6f});
        table.setWidthPercentage(100);
        table.addCell(headerCell("Supplier disbursement (reimbursement - no GST)", labelFont));
        table.addCell(headerCell("Amount", labelFont));

        BigDecimal disbursementTotal = BigDecimal.ZERO;
        if (!costComponents.isEmpty()) {
            for (BookingCostComponent c : costComponents) {
                String label = (c.getVendorName() != null ? c.getVendorName() : "Supplier")
                        + (c.getDescription() != null && !c.getDescription().isBlank() ? " - " + c.getDescription() : "");
                table.addCell(new PdfPCell(new Phrase(label, bodyFont)));
                rightCell(table, money(c.getNetCostInr()), bodyFont);
                disbursementTotal = disbursementTotal.add(c.getNetCostInr() != null ? c.getNetCostInr() : BigDecimal.ZERO);
            }
        } else {
            table.addCell(new PdfPCell(new Phrase("Supplier cost", bodyFont)));
            rightCell(table, money(disbursementTotal), bodyFont);
        }

        BigDecimal fee = invoice.getTaxableValue().subtract(disbursementTotal);
        if (fee.compareTo(BigDecimal.ZERO) < 0) {
            fee = BigDecimal.ZERO;
        }
        PdfPCell feeLabel = new PdfPCell(new Phrase("Agency service fee", labelFont));
        PdfPCell feeValue = new PdfPCell(new Phrase(money(fee), labelFont));
        feeValue.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.addCell(feeLabel);
        table.addCell(feeValue);

        PdfPCell note = new PdfPCell(new Phrase(
                "Supplier disbursement is a reimbursement of costs paid on the traveller's behalf - GST applies "
                        + "only to the agency's own service fee.", smallFont));
        note.setColspan(2);
        note.setBorder(0);
        table.addCell(note);
        return table;
    }

    /**
     * A hidden tax is never a line of its own here - its amount is folded straight into the
     * printed "Amount" so the two totals (what's itemised, what's charged) never diverge. When
     * every tax on the invoice is hidden, no tax breakup prints at all - just one fare figure,
     * matching the client's own reimbursement-style invoice (ACCOUNTING_REDESIGN_SPEC.md §3).
     */
    private static PdfPTable totalsTable(Invoice invoice, List<InvoiceTax> taxes, Font labelFont, Font bodyFont) {
        PdfPTable table = new PdfPTable(new float[]{3f, 1.5f});
        table.setWidthPercentage(50);
        table.setHorizontalAlignment(Element.ALIGN_RIGHT);

        BigDecimal hiddenTotal = taxes.stream()
                .filter(t -> !Boolean.TRUE.equals(t.getVisibleToCustomer()))
                .map(InvoiceTax::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal displayedFare = invoice.getTaxableValue().add(hiddenTotal);

        totalRow(table, "Amount", money(displayedFare), bodyFont);
        for (InvoiceTax tax : taxes) {
            if (Boolean.TRUE.equals(tax.getVisibleToCustomer()) && tax.getAmount().compareTo(BigDecimal.ZERO) != 0) {
                totalRow(table, tax.getLabel(), money(tax.getAmount()), bodyFont);
            }
        }
        if (invoice.getRoundOff().compareTo(BigDecimal.ZERO) != 0) {
            totalRow(table, "Round Off", money(invoice.getRoundOff()), bodyFont);
        }
        // Matches the source system's own dual-currency block (spec §2.5/§3): INR total
        // always shown, the invoice's own currency shown alongside it when that isn't INR.
        // "INR" not "₹" - OpenPDF's base Helvetica (WinAnsiEncoding) has no glyph for U+20B9
        // and silently drops it, leaving "Total Amount in" with nothing after it.
        totalRow(table, "Total Amount in INR", money(invoice.getGrandTotalInr()), labelFont);
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
