package com.voyra.crm.service;

import com.voyra.crm.dto.AccountsDashboardSummaryResponse;
import com.voyra.crm.dto.GstSummaryRowResponse;
import com.voyra.crm.dto.TcsSummaryRowResponse;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.entity.InvoiceTax;
import com.voyra.crm.entity.PaymentReceipt;
import com.voyra.crm.enums.CreditNoteStatus;
import com.voyra.crm.enums.InvoiceDocumentType;
import com.voyra.crm.enums.InvoiceLifecycle;
import com.voyra.crm.enums.ReceiptDirection;
import com.voyra.crm.enums.TaxKind;
import com.voyra.crm.repository.CreditNoteRepository;
import com.voyra.crm.repository.InvoiceRepository;
import com.voyra.crm.repository.InvoiceTaxRepository;
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
    private final InvoiceTaxRepository invoiceTaxRepository;
    private final PaymentReceiptRepository paymentReceiptRepository;
    private final CreditNoteRepository creditNoteRepository;
    private final com.voyra.crm.repository.JournalLineRepository journalLineRepository;
    private final com.voyra.crm.repository.JournalEntryRepository journalEntryRepository;
    private final com.voyra.crm.repository.TaxRateConfigRepository taxRateConfigRepository;

    @Transactional(readOnly = true)
    public AccountsDashboardSummaryResponse summary() {
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate monthEnd = today.withDayOfMonth(today.lengthOfMonth());

        List<Invoice> thisMonth = invoiceRepository.findByDocumentTypeAndStatusNotAndInvoiceDateBetween(
                InvoiceDocumentType.TAX_INVOICE, InvoiceLifecycle.CANCELLED, monthStart, monthEnd);
        BigDecimal billedThisMonth = sum(thisMonth, Invoice::getGrandTotalInr);
        BigDecimal outputTaxThisMonth = sum(thisMonth, Invoice::getGstTotalInr);
        BigDecimal netRevenueThisMonth = trueRevenue(monthStart, monthEnd);

        List<PaymentReceipt> collectedThisMonth = paymentReceiptRepository.findByDirectionAndReceivedOnBetween(
                ReceiptDirection.RECEIPT, monthStart, monthEnd);
        // Adjustment receipts are wallet transfers (a refund kept on account, a deposit applied to an
        // invoice) - no money reached the bank, so counting them would double the deposit.
        BigDecimal collected = sum(collectedThisMonth.stream()
                .filter(r -> r.getPaymentMode() != com.voyra.crm.enums.PaymentMode.ADJUSTMENT).toList(), PaymentReceipt::getAmountInr);

        List<Invoice> unpaid = invoiceRepository.findByDocumentTypeAndStatusInAndBalanceDueInrGreaterThan(
                InvoiceDocumentType.TAX_INVOICE, List.of(InvoiceLifecycle.ISSUED, InvoiceLifecycle.PARTIALLY_PAID), BigDecimal.ZERO);
        BigDecimal outstanding = sum(unpaid, Invoice::getBalanceDueInr);
        BigDecimal overdue = unpaid.stream()
                .filter(i -> i.getDueDate() != null && i.getDueDate().isBefore(today))
                .map(Invoice::getBalanceDueInr)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Everything held for clients - booking advances, deposits, refunds kept on account - is the
        // balance of Client Advances Held, the same figure the GL card shows.
        String advances = com.voyra.crm.enums.SystemAccount.CLIENT_ADVANCES_HELD.code();
        LocalDate after = today.plusDays(1);
        BigDecimal advanceHeld = journalLineRepository.sumCreditBefore(advances, after)
                .subtract(journalLineRepository.sumDebitBefore(advances, after)).max(BigDecimal.ZERO);

        return AccountsDashboardSummaryResponse.builder()
                .billedThisMonthInr(billedThisMonth)
                .collectedThisMonthInr(collected)
                .outstandingInr(outstanding)
                .overdueInr(overdue)
                .advanceHeldInr(advanceHeld)
                .outputTaxThisMonthInr(outputTaxThisMonth)
                .netRevenueThisMonthInr(netRevenueThisMonth)
                .build();
    }

    /**
     * GST-exclusive economic revenue for a period: issued, non-cancelled tax invoices' taxable
     * value, net of the same period's issued credit notes' taxable value (converted at each
     * document's own locked rate) - TCS excluded, since it is collected on the government's
     * behalf, not the agency's income. Distinct from the sales-pipeline metric
     * {@code SUM(booking.sellingPrice)} used elsewhere (agent leaderboard, revenue trend chart):
     * that figure tracks what was sold, this one tracks what was actually billed and not since
     * reversed - the two are expected to differ by exactly the value of cancelled bookings (never
     * billed) plus any credit notes (billed, then reversed).
     */
    @Transactional(readOnly = true)
    public BigDecimal trueRevenue(LocalDate from, LocalDate to) {
        BigDecimal billedTaxable = sum(taxInvoicesInRange(from, to), Invoice::getTaxableValueInr);
        BigDecimal creditedTaxable = creditNoteRepository.findByStatusAndNoteDateBetween(CreditNoteStatus.ISSUED, from, to)
                .stream()
                .map(cn -> scale(cn.getTaxableValue().multiply(cn.getFxRateToInr())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return billedTaxable.subtract(creditedTaxable);
    }

    /**
     * Groups by SAC and GST rate over issued, non-cancelled tax invoices in range - the input a CA
     * needs for GSTR-1, not a filing itself. Tax is opt-in per invoice (see {@link InvoiceTax}), so
     * this reads the invoice's own chosen GST-kind rows. The SAC is the one on the tax slab the row
     * was picked from ("-" for a custom tax).
     */
    @Transactional(readOnly = true)
    public List<GstSummaryRowResponse> gstSummary(LocalDate from, LocalDate to) {
        Map<String, BigDecimal[]> byGroup = new LinkedHashMap<>();
        Map<String, String[]> sacRateByKey = new LinkedHashMap<>();

        List<Invoice> invoices = taxInvoicesInRange(from, to);
        Map<String, List<InvoiceTax>> taxesByInvoice = new LinkedHashMap<>();
        for (Invoice invoice : invoices) {
            taxesByInvoice.put(invoice.getId(), invoiceTaxRepository.findByInvoiceIdOrderBySortOrderAsc(invoice.getId()));
        }
        Map<String, String> sacByConfig = new java.util.HashMap<>();
        taxRateConfigRepository.findAllById(taxesByInvoice.values().stream().flatMap(List::stream)
                        .map(InvoiceTax::getTaxRateConfigId).filter(java.util.Objects::nonNull).distinct().toList())
                .forEach(c -> { if (c.getSacCode() != null) sacByConfig.put(c.getId(), c.getSacCode()); });

        for (Invoice invoice : invoices) {
            BigDecimal fx = invoice.getFxRateToInr();
            BigDecimal taxableInr = null;
            for (InvoiceTax tax : taxesByInvoice.get(invoice.getId())) {
                if (tax.getTaxKind() != TaxKind.GST) {
                    continue;
                }
                if (taxableInr == null) {
                    taxableInr = gstBaseInr(invoice);
                }
                String rate = (tax.getRatePercent() != null ? tax.getRatePercent() : BigDecimal.ZERO)
                        .stripTrailingZeros().toPlainString();
                String sac = tax.getTaxRateConfigId() != null ? sacByConfig.getOrDefault(tax.getTaxRateConfigId(), "-") : "-";
                String key = sac + "|" + rate;
                BigDecimal[] agg = byGroup.computeIfAbsent(key, k -> zeros(4));
                agg[0] = agg[0].add(taxableInr);
                agg[1] = agg[1].add(scale(tax.getCgstAmount().multiply(fx)));
                agg[2] = agg[2].add(scale(tax.getSgstAmount().multiply(fx)));
                agg[3] = agg[3].add(scale(tax.getIgstAmount().multiply(fx)));
                sacRateByKey.putIfAbsent(key, new String[]{sac, rate});
            }
        }

        return byGroup.entrySet().stream()
                .map(e -> {
                    BigDecimal[] a = e.getValue();
                    String[] sacRate = sacRateByKey.get(e.getKey());
                    return GstSummaryRowResponse.builder()
                            .sacCode(sacRate[0]).gstRatePercent(new BigDecimal(sacRate[1]))
                            .taxableValueInr(a[0]).cgstAmountInr(a[1]).sgstAmountInr(a[2]).igstAmountInr(a[3])
                            .gstTotalInr(a[1].add(a[2]).add(a[3]))
                            .build();
                })
                .toList();
    }

    /**
     * The value GST was levied on. {@code taxableValueInr} is the whole package for every billing
     * model (the pass-through block and the journal are built from it); a commission-agent invoice
     * taxes only the agency's fee, which is exactly what its own posted journal credited to 4600.
     */
    private BigDecimal gstBaseInr(Invoice invoice) {
        if (invoice.getBillingModel() != com.voyra.crm.enums.InvoiceBillingModel.COMMISSION_AGENT) {
            return invoice.getTaxableValueInr();
        }
        String feeAccount = com.voyra.crm.enums.SystemAccount.SERVICE_FEE_INCOME.code();
        return journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(com.voyra.crm.enums.JournalSourceType.INVOICE,
                        invoice.getId(), com.voyra.crm.enums.JournalPurpose.INVOICE_RAISED_COMMISSION_AGENT)
                .map(entry -> journalLineRepository.findByJournalEntryIdOrderByLineNo(entry.getId()).stream()
                        .filter(l -> feeAccount.equals(l.getAccountCode()))
                        .map(l -> l.getCreditAmountInr() != null ? l.getCreditAmountInr() : BigDecimal.ZERO)
                        .reduce(BigDecimal.ZERO, BigDecimal::add))
                .orElse(invoice.getTaxableValueInr());
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
        // A draft is not a supply yet - it has no number and is not in any GST return.
        return invoiceRepository.findByDocumentTypeAndStatusNotAndInvoiceDateBetween(
                InvoiceDocumentType.TAX_INVOICE, InvoiceLifecycle.CANCELLED, from, to).stream()
                .filter(i -> i.getStatus() != InvoiceLifecycle.DRAFT).toList();
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
