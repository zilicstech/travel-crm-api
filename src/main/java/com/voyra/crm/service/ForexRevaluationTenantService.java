package com.voyra.crm.service;

import com.voyra.crm.entity.JournalEntry;
import com.voyra.crm.entity.SupplierInvoice;
import com.voyra.crm.enums.JournalPurpose;
import com.voyra.crm.enums.JournalSourceType;
import com.voyra.crm.enums.JournalStatus;
import com.voyra.crm.enums.SupplierInvoiceStatus;
import com.voyra.crm.enums.SystemAccount;
import com.voyra.crm.models.JournalLinePosting;
import com.voyra.crm.models.JournalPosting;
import com.voyra.crm.repository.JournalEntryRepository;
import com.voyra.crm.repository.SupplierInvoiceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * ACCOUNTING_EXPANSION_ARCHITECTURE.md §3.5 - the month-end mark-to-market half of the forex
 * engine. Every open, non-INR supplier bill gets its outstanding balance revalued at the
 * period-end rate; the entry is reversed before the next period's fresh one posts (Rule 3.5.2),
 * so the bill's carrying value never permanently drifts from what it was actually booked at.
 *
 * <p>Separate {@code REQUIRES_NEW} bean per {@code BACKEND_BLUEPRINT.md} §3.5 - the scheduler
 * has no request-scoped {@code TenantContext}, and self-invocation would bypass the proxy and
 * run against whatever schema the caller's connection already had.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ForexRevaluationTenantService {

    private static final List<SupplierInvoiceStatus> OPEN_STATUSES =
            List.of(SupplierInvoiceStatus.APPROVED, SupplierInvoiceStatus.PARTIALLY_PAID);
    private static final DateTimeFormatter PERIOD_KEY = DateTimeFormatter.ofPattern("yyyyMM");

    private final SupplierInvoiceRepository supplierInvoiceRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final JournalService journalService;
    private final ExchangeRateProvider exchangeRateProvider;

    public record RevaluationResult(int revalued) {
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RevaluationResult revalueOpenForeignBills(LocalDate monthEnd) {
        List<SupplierInvoice> openForeignBills = supplierInvoiceRepository
                .findByCurrencyCodeNotAndBalanceDueGreaterThanAndStatusIn("INR", BigDecimal.ZERO, OPEN_STATUSES);

        int revalued = 0;
        for (SupplierInvoice bill : openForeignBills) {
            reverseLastPeriodsRevaluation(bill, monthEnd);

            BigDecimal monthEndRate;
            try {
                monthEndRate = exchangeRateProvider.resolve(bill.getCurrencyCode(), "INR", monthEnd).rate();
            } catch (IllegalStateException e) {
                log.warn("No exchange rate for {}/INR as of {} - skipping revaluation of bill {}: {}",
                        bill.getCurrencyCode(), monthEnd, bill.getId(), e.getMessage());
                continue;
            }

            BigDecimal carryingRate = bill.getFxRateToInr();
            BigDecimal delta = bill.getBalanceDue().multiply(monthEndRate.subtract(carryingRate)).setScale(2, RoundingMode.HALF_UP);
            if (delta.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            postRevaluation(bill, monthEnd, delta);
            revalued++;
        }
        return new RevaluationResult(revalued);
    }

    /** Rule 3.5.2 - frees the prior period's slot in the bill's unified "one open revaluation" lifecycle before this period posts a fresh one. */
    private void reverseLastPeriodsRevaluation(SupplierInvoice bill, LocalDate monthEnd) {
        LocalDate priorMonthEnd = monthEnd.minusMonths(1).withDayOfMonth(monthEnd.minusMonths(1).lengthOfMonth());
        String priorSourceId = periodSourceId(bill.getId(), priorMonthEnd);
        journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(
                JournalSourceType.SUPPLIER_INVOICE, priorSourceId, JournalPurpose.FOREX_REVALUATION)
                .filter(entry -> entry.getStatus() == JournalStatus.POSTED)
                .ifPresent(entry -> journalService.reverse(entry.getId(),
                        "Unrealized revaluation superseded by " + monthEnd + "'s mark-to-market"));
    }

    /** Rule 18: a rate RISE on a payable costs more INR to settle later (a loss, 5710); a FALL costs less (a gain, 4710). */
    private void postRevaluation(SupplierInvoice bill, LocalDate monthEnd, BigDecimal delta) {
        BigDecimal amount = delta.abs();
        String narration = "Unrealized forex revaluation - bill " + orBillNumber(bill) + " as of " + monthEnd;
        List<JournalLinePosting> lines = delta.signum() > 0
                ? List.of(
                        JournalLinePosting.debit(SystemAccount.UNREALIZED_FOREX_LOSS.code(), amount, narration),
                        JournalLinePosting.creditParty(SystemAccount.ACCOUNTS_PAYABLE.code(), "VENDOR", bill.getVendorId(), amount, narration))
                : List.of(
                        JournalLinePosting.debitParty(SystemAccount.ACCOUNTS_PAYABLE.code(), "VENDOR", bill.getVendorId(), amount, narration),
                        JournalLinePosting.credit(SystemAccount.UNREALIZED_FOREX_GAIN.code(), amount, narration));

        JournalEntry entry = journalService.post(new JournalPosting(
                monthEnd, JournalSourceType.SUPPLIER_INVOICE, periodSourceId(bill.getId(), monthEnd),
                JournalPurpose.FOREX_REVALUATION, narration, bill.getBookingId(), null, lines));
        log.info("Forex revaluation posted: billId={}, monthEnd={}, delta={}, entryId={}",
                bill.getId(), monthEnd, delta, entry.getId());
    }

    /** "<bill id>@<yyyyMM>" - each period's revaluation is a distinct event (V47 widened source_id to fit this). */
    private static String periodSourceId(String billId, LocalDate periodEnd) {
        return billId + "@" + periodEnd.format(PERIOD_KEY);
    }

    private static String orBillNumber(SupplierInvoice bill) {
        return bill.getSupplierInvoiceNumber() != null ? bill.getSupplierInvoiceNumber() : bill.getId();
    }
}
