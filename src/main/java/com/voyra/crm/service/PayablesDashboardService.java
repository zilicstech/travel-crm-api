package com.voyra.crm.service;

import com.voyra.crm.dto.CostReconciliationRowResponse;
import com.voyra.crm.dto.PayablesDashboardSummaryResponse;
import com.voyra.crm.dto.PurchaseRegisterRowResponse;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.SupplierInvoice;
import com.voyra.crm.entity.SupplierInvoiceLineItem;
import com.voyra.crm.entity.SupplierPayment;
import com.voyra.crm.enums.SupplierInvoiceStatus;
import com.voyra.crm.enums.SupplierPaymentDirection;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.SupplierInvoiceLineItemRepository;
import com.voyra.crm.repository.SupplierInvoiceRepository;
import com.voyra.crm.repository.SupplierPaymentRepository;
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
 * Read-only aggregates over {@link SupplierInvoice}/{@link SupplierPayment} for the Payables
 * dashboard, the Purchase &amp; ITC register, and the Cost Reconciliation report - the
 * accounts-payable mirror of {@code AccountsDashboardService}. Cost Reconciliation never writes
 * anything back to {@link Booking#getNetCost()}/{@code profit} - see ARCHITECTURE-SPINE AD-9;
 * this service is entirely read-only.
 */
@Service
@RequiredArgsConstructor
public class PayablesDashboardService {

    private final SupplierInvoiceRepository supplierInvoiceRepository;
    private final SupplierInvoiceLineItemRepository lineItemRepository;
    private final SupplierPaymentRepository supplierPaymentRepository;
    private final BookingRepository bookingRepository;

    @Transactional(readOnly = true)
    public PayablesDashboardSummaryResponse summary() {
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate monthEnd = today.withDayOfMonth(today.lengthOfMonth());

        List<SupplierInvoice> thisMonth = supplierInvoiceRepository.findByStatusNotAndInvoiceDateBetween(
                SupplierInvoiceStatus.CANCELLED, monthStart, monthEnd);
        BigDecimal billedThisMonth = sum(thisMonth, SupplierInvoice::getGrandTotalInr);
        BigDecimal inputTaxThisMonth = sum(thisMonth, SupplierInvoice::getGstTotalInr);

        List<SupplierPayment> paidThisMonth = supplierPaymentRepository.findByDirectionAndPaidOnBetween(
                SupplierPaymentDirection.PAYMENT, monthStart, monthEnd);
        BigDecimal paid = paidThisMonth.stream()
                .filter(p -> !p.getIsAdvance() && p.getReversesPaymentId() == null)
                .map(SupplierPayment::getAmountInr)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<SupplierInvoice> unpaid = supplierInvoiceRepository.findAll().stream()
                .filter(i -> (i.getStatus() == SupplierInvoiceStatus.APPROVED || i.getStatus() == SupplierInvoiceStatus.PARTIALLY_PAID)
                        && i.getBalanceDueInr().compareTo(BigDecimal.ZERO) > 0)
                .toList();
        BigDecimal outstanding = sum(unpaid, SupplierInvoice::getBalanceDueInr);
        BigDecimal overdue = unpaid.stream()
                .filter(i -> i.getDueDate() != null && i.getDueDate().isBefore(today))
                .map(SupplierInvoice::getBalanceDueInr)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal advancesHeld = supplierPaymentRepository.findAll().stream()
                .filter(p -> Boolean.TRUE.equals(p.getIsAdvance()) && p.getReversedAt() == null)
                .map(SupplierPayment::getAmountInr)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .subtract(supplierPaymentRepository.findAll().stream()
                        .filter(p -> Boolean.TRUE.equals(p.getAppliedFromAdvance()) && p.getReversedAt() == null)
                        .map(SupplierPayment::getAmountInr)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));

        long draftCount = supplierInvoiceRepository.countByStatus(SupplierInvoiceStatus.DRAFT);

        return PayablesDashboardSummaryResponse.builder()
                .payableOutstandingInr(outstanding)
                .overdueInr(overdue)
                .advancesHeldInr(advancesHeld.max(BigDecimal.ZERO))
                .inputTaxThisMonthInr(inputTaxThisMonth)
                .billsAwaitingApprovalCount(draftCount)
                .billedThisMonthInr(billedThisMonth)
                .paidThisMonthInr(paid)
                .build();
    }

    /** Groups by (SAC code, GST rate, ITC eligibility) over approved, non-cancelled bills in range. */
    @Transactional(readOnly = true)
    public List<PurchaseRegisterRowResponse> purchaseRegister(LocalDate from, LocalDate to) {
        Map<String, BigDecimal[]> byGroup = new LinkedHashMap<>();
        Map<String, Object[]> keyParts = new LinkedHashMap<>();

        for (SupplierInvoice invoice : billsInRange(from, to)) {
            BigDecimal fx = invoice.getFxRateToInr();
            for (SupplierInvoiceLineItem line : lineItemRepository.findBySupplierInvoiceIdOrderBySortOrder(invoice.getId())) {
                String sac = line.getSacCode() != null ? line.getSacCode() : "-";
                String rate = line.getGstRatePercent().stripTrailingZeros().toPlainString();
                String key = sac + "|" + rate + "|" + invoice.getItcEligibility();
                BigDecimal[] agg = byGroup.computeIfAbsent(key, k -> zeros(4));
                agg[0] = agg[0].add(scale(line.getTaxableValue().multiply(fx)));
                agg[1] = agg[1].add(scale(line.getCgstAmount().multiply(fx)));
                agg[2] = agg[2].add(scale(line.getSgstAmount().multiply(fx)));
                agg[3] = agg[3].add(scale(line.getIgstAmount().multiply(fx)));
                keyParts.putIfAbsent(key, new Object[]{sac, rate, invoice.getItcEligibility()});
            }
        }

        return byGroup.entrySet().stream()
                .map(e -> {
                    BigDecimal[] a = e.getValue();
                    Object[] kp = keyParts.get(e.getKey());
                    return PurchaseRegisterRowResponse.builder()
                            .sacCode((String) kp[0]).gstRatePercent(new BigDecimal((String) kp[1]))
                            .itcEligibility((com.voyra.crm.enums.ItcEligibility) kp[2])
                            .taxableValueInr(a[0]).cgstAmountInr(a[1]).sgstAmountInr(a[2]).igstAmountInr(a[3])
                            .gstTotalInr(a[1].add(a[2]).add(a[3]))
                            .build();
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public byte[] exportPurchaseRegisterXlsx(LocalDate from, LocalDate to) {
        List<PurchaseRegisterRowResponse> rows = purchaseRegister(from, to);
        List<String> header = List.of("SAC Code", "GST Rate %", "ITC Eligibility", "Taxable Value (INR)",
                "CGST (INR)", "SGST (INR)", "IGST (INR)", "GST Total (INR)");
        List<List<String>> data = rows.stream()
                .map(r -> List.of(r.getSacCode(), str(r.getGstRatePercent()), r.getItcEligibility().name(),
                        str(r.getTaxableValueInr()), str(r.getCgstAmountInr()), str(r.getSgstAmountInr()),
                        str(r.getIgstAmountInr()), str(r.getGstTotalInr())))
                .toList();
        return XlsxWriter.write("Purchase & ITC Register", new ReportTable(header, data));
    }

    /**
     * Read-only, per ARCHITECTURE-SPINE AD-9: {@link Booking#getNetCost()} is the agent's own
     * estimate, unchanged. actualCostInr sums every approved, non-cancelled supplier bill against
     * the booking - a booking with no bills yet is simply omitted, not shown as zero variance.
     */
    @Transactional(readOnly = true)
    public List<CostReconciliationRowResponse> costReconciliation() {
        List<SupplierInvoice> allBills = supplierInvoiceRepository.findAll().stream()
                .filter(i -> i.getStatus() != SupplierInvoiceStatus.CANCELLED && i.getStatus() != SupplierInvoiceStatus.DRAFT)
                .filter(i -> i.getBookingId() != null)
                .toList();
        Map<String, BigDecimal> actualByBooking = new LinkedHashMap<>();
        for (SupplierInvoice bill : allBills) {
            actualByBooking.merge(bill.getBookingId(), bill.getGrandTotalInr(), BigDecimal::add);
        }

        List<CostReconciliationRowResponse> rows = new java.util.ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : actualByBooking.entrySet()) {
            Booking booking = bookingRepository.findById(e.getKey()).orElse(null);
            if (booking == null) {
                continue;
            }
            BigDecimal estimated = booking.getNetCost() != null ? booking.getNetCost() : BigDecimal.ZERO;
            BigDecimal actual = e.getValue();
            BigDecimal variance = actual.subtract(estimated);
            BigDecimal variancePercent = estimated.compareTo(BigDecimal.ZERO) == 0 ? null
                    : variance.multiply(BigDecimal.valueOf(100)).divide(estimated, 2, RoundingMode.HALF_UP);
            rows.add(CostReconciliationRowResponse.builder()
                    .bookingId(booking.getId()).clientName(booking.getClientName()).destination(booking.getDestination())
                    .estimatedCostInr(estimated).actualCostInr(actual).varianceInr(variance).variancePercent(variancePercent)
                    .build());
        }
        return rows;
    }

    private List<SupplierInvoice> billsInRange(LocalDate from, LocalDate to) {
        return supplierInvoiceRepository.findByStatusNotAndInvoiceDateBetween(SupplierInvoiceStatus.CANCELLED, from, to).stream()
                .filter(i -> i.getStatus() != SupplierInvoiceStatus.DRAFT)
                .toList();
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
