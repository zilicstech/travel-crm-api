package com.voyra.crm.service;

import com.voyra.crm.dto.AccountsDashboardSummaryResponse;
import com.voyra.crm.dto.GstSummaryRowResponse;
import com.voyra.crm.dto.TcsSummaryRowResponse;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.entity.InvoiceLineItem;
import com.voyra.crm.entity.PaymentReceipt;
import com.voyra.crm.enums.InvoiceDocumentType;
import com.voyra.crm.enums.InvoiceLifecycle;
import com.voyra.crm.enums.ReceiptDirection;
import com.voyra.crm.repository.InvoiceLineItemRepository;
import com.voyra.crm.repository.InvoiceRepository;
import com.voyra.crm.repository.PaymentReceiptRepository;
import com.voyra.crm.util.ReportTable;
import com.voyra.crm.util.XlsxWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Read-only aggregates over {@link Invoice} and {@link PaymentReceipt} for the accounts
 * dashboard and the GST/TCS registers. Joins line items to their invoice in Java, never via a
 * JPA association (blueprint §8.4) - the same pattern {@code InvoiceDocumentService} uses.
 * TCS is deliberately excluded from every revenue-shaped figure here: it is collected on the
 * government's behalf, not the agency's income.
 */
@Service
@RequiredArgsConstructor
public class AccountsDashboardService {

    private final InvoiceRepository invoiceRepository;
    private final InvoiceLineItemRepository invoiceLineItemRepository;
    private final PaymentReceiptRepository paymentReceiptRepository;

    @Transactional(readOnly = true)
    public AccountsDashboardSummaryResponse summary() {
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate monthEnd = today.withDayOfMonth(today.lengthOfMonth());

        List<Invoice> thisMonth = invoiceRepository.findByDocumentTypeAndStatusNotAndInvoiceDateBetween(
                InvoiceDocumentType.TAX_INVOICE, InvoiceLifecycle.CANCELLED, monthStart, monthEnd);
        BigDecimal billedThisMonth = sum(thisMonth, Invoice::getGrandTotalInr);
        BigDecimal outputTaxThisMonth = sum(thisMonth, Invoice::getGstTotalInr);

        List<PaymentReceipt> collectedThisMonth = paymentReceiptRepository.findByDirectionAndReceivedOnBetween(
                ReceiptDirection.RECEIPT, monthStart, monthEnd);
        BigDecimal collected = sum(collectedThisMonth, PaymentReceipt::getAmountInr);

        List<Invoice> unpaid = invoiceRepository.findByDocumentTypeAndStatusInAndBalanceDueInrGreaterThan(
                InvoiceDocumentType.TAX_INVOICE, List.of(InvoiceLifecycle.ISSUED, InvoiceLifecycle.PARTIALLY_PAID), BigDecimal.ZERO);
        BigDecimal outstanding = sum(unpaid, Invoice::getBalanceDueInr);
        BigDecimal overdue = unpaid.stream()
                .filter(i -> i.getDueDate() != null && i.getDueDate().isBefore(today))
                .map(Invoice::getBalanceDueInr)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal advanceHeld = BigDecimal.ZERO;
        for (PaymentReceipt r : paymentReceiptRepository.findByDirectionAndIsAdvanceTrue(ReceiptDirection.RECEIPT)) {
            Invoice invoice = r.getInvoiceId() != null ? invoiceRepository.findById(r.getInvoiceId()).orElse(null) : null;
            if (invoice != null && invoice.getStatus() == InvoiceLifecycle.PROFORMA_ISSUED) {
                advanceHeld = advanceHeld.add(r.getAmountInr());
            }
        }

        return AccountsDashboardSummaryResponse.builder()
                .billedThisMonthInr(billedThisMonth)
                .collectedThisMonthInr(collected)
                .outstandingInr(outstanding)
                .overdueInr(overdue)
                .advanceHeldInr(advanceHeld)
                .outputTaxThisMonthInr(outputTaxThisMonth)
                .build();
    }

    /** Groups by (SAC code, GST rate) over issued, non-cancelled tax invoices in range - the input a CA needs for GSTR-1, not a filing itself. */
    @Transactional(readOnly = true)
    public List<GstSummaryRowResponse> gstSummary(LocalDate from, LocalDate to) {
        Map<String, BigDecimal[]> byGroup = new LinkedHashMap<>();
        Map<String, String[]> keyParts = new LinkedHashMap<>();

        for (Invoice invoice : taxInvoicesInRange(from, to)) {
            BigDecimal fx = invoice.getFxRateToInr();
            for (InvoiceLineItem line : invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc(invoice.getId())) {
                String sac = line.getSacCode() != null ? line.getSacCode() : "-";
                String rate = line.getGstRatePercent().stripTrailingZeros().toPlainString();
                String key = sac + "|" + rate;
                BigDecimal[] agg = byGroup.computeIfAbsent(key, k -> zeros(4));
                agg[0] = agg[0].add(scale(line.getTaxableValue().multiply(fx)));
                agg[1] = agg[1].add(scale(line.getCgstAmount().multiply(fx)));
                agg[2] = agg[2].add(scale(line.getSgstAmount().multiply(fx)));
                agg[3] = agg[3].add(scale(line.getIgstAmount().multiply(fx)));
                keyParts.putIfAbsent(key, new String[]{sac, rate});
            }
        }

        return byGroup.entrySet().stream()
                .map(e -> {
                    BigDecimal[] a = e.getValue();
                    String[] kp = keyParts.get(e.getKey());
                    return GstSummaryRowResponse.builder()
                            .sacCode(kp[0]).gstRatePercent(new BigDecimal(kp[1]))
                            .taxableValueInr(a[0]).cgstAmountInr(a[1]).sgstAmountInr(a[2]).igstAmountInr(a[3])
                            .gstTotalInr(a[1].add(a[2]).add(a[3]))
                            .build();
                })
                .toList();
    }

    /** Groups by TCS section over issued, non-cancelled tax invoices in range that actually carry TCS. */
    @Transactional(readOnly = true)
    public List<TcsSummaryRowResponse> tcsSummary(LocalDate from, LocalDate to) {
        Map<String, BigDecimal[]> byGroup = new LinkedHashMap<>();
        Map<String, BigDecimal> rateBySection = new LinkedHashMap<>();

        for (Invoice invoice : taxInvoicesInRange(from, to)) {
            if (invoice.getTcsAmountInr().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            String section = invoice.getTcsSection() != null ? invoice.getTcsSection() : "-";
            BigDecimal[] agg = byGroup.computeIfAbsent(section, k -> zeros(2));
            agg[0] = agg[0].add(scale(invoice.getTcsBaseAmount().multiply(invoice.getFxRateToInr())));
            agg[1] = agg[1].add(invoice.getTcsAmountInr());
            rateBySection.putIfAbsent(section, invoice.getTcsRatePercent());
        }

        return byGroup.entrySet().stream()
                .map(e -> TcsSummaryRowResponse.builder()
                        .tcsSection(e.getKey()).tcsRatePercent(rateBySection.get(e.getKey()))
                        .tcsBaseAmountInr(e.getValue()[0]).tcsAmountInr(e.getValue()[1])
                        .build())
                .toList();
    }

    @Transactional(readOnly = true)
    public byte[] exportGstXlsx(LocalDate from, LocalDate to) {
        List<GstSummaryRowResponse> rows = gstSummary(from, to);
        List<String> header = List.of("SAC Code", "GST Rate %", "Taxable Value (INR)",
                "CGST (INR)", "SGST (INR)", "IGST (INR)", "GST Total (INR)");
        List<List<String>> data = rows.stream()
                .map(r -> List.of(r.getSacCode(), str(r.getGstRatePercent()), str(r.getTaxableValueInr()),
                        str(r.getCgstAmountInr()), str(r.getSgstAmountInr()), str(r.getIgstAmountInr()), str(r.getGstTotalInr())))
                .toList();
        return XlsxWriter.write("GST Register", new ReportTable(header, data));
    }

    private List<Invoice> taxInvoicesInRange(LocalDate from, LocalDate to) {
        return invoiceRepository.findByDocumentTypeAndStatusNotAndInvoiceDateBetween(
                InvoiceDocumentType.TAX_INVOICE, InvoiceLifecycle.CANCELLED, from, to);
    }

    private static <T> BigDecimal sum(List<T> items, Function<T, BigDecimal> extractor) {
        return items.stream().map(extractor).filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal[] zeros(int count) {
        BigDecimal[] a = new BigDecimal[count];
        java.util.Arrays.fill(a, BigDecimal.ZERO);
        return a;
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String str(Object value) {
        return value != null ? value.toString() : "";
    }
}
