package com.voyra.crm.service;

import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.enums.InvoiceLifecycle;
import com.voyra.crm.enums.InvoiceServiceCategory;
import com.voyra.crm.enums.JournalPurpose;
import com.voyra.crm.enums.JournalSourceType;
import com.voyra.crm.enums.SystemAccount;
import com.voyra.crm.models.JournalLinePosting;
import com.voyra.crm.models.JournalPosting;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.InvoiceRepository;
import com.voyra.crm.repository.JournalEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Posting-rule-table row 7 (ACCOUNTING_EXPANSION_ARCHITECTURE.md §2) - moves a PRINCIPAL
 * invoice's taxable value from Unearned Tour Revenue (2120) to the category's own Sales account
 * once the booking's departure has arrived. Runs on whichever tenant schema
 * {@link com.voyra.crm.context.TenantContext} currently points at, in its own fresh transaction -
 * same reasoning as {@link LedgerIntegrityCheckService}: a scheduled job has no request-scoped
 * tenant transaction to join, so this must live on its own {@code REQUIRES_NEW} bean.
 *
 * <p>Rule 2.4.1/2.4.2 - idempotency is the journal's own existence, not a new column. The query
 * never asks "what departs today"; it asks "every live invoice whose booking has arrived and
 * which has no REVENUE_RECOGNIZED entry yet", so catch-up after missed days and safe re-runs on
 * the same day both fall out of the same query for free.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RevenueRecognitionTenantService {

    private static final List<InvoiceLifecycle> LIVE_INVOICED_STATUSES =
            List.of(InvoiceLifecycle.ISSUED, InvoiceLifecycle.PARTIALLY_PAID, InvoiceLifecycle.PAID);

    private final BookingRepository bookingRepository;
    private final InvoiceRepository invoiceRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final JournalService journalService;

    public record RecognitionResult(int eligible, int recognized) {
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RecognitionResult recognizeDue(LocalDate asOf) {
        List<Booking> arrivedBookings = bookingRepository.findByDepartureDateLessThanEqual(asOf);
        if (arrivedBookings.isEmpty()) {
            return new RecognitionResult(0, 0);
        }
        List<String> bookingIds = arrivedBookings.stream().map(Booking::getId).toList();

        List<Invoice> invoices = invoiceRepository.findByBookingIdInAndStatusIn(bookingIds, LIVE_INVOICED_STATUSES);
        int recognized = 0;
        for (Invoice invoice : invoices) {
            if (journalEntryRepository.existsBySourceTypeAndSourceIdAndPurpose(
                    JournalSourceType.INVOICE, invoice.getId(), JournalPurpose.REVENUE_RECOGNIZED)) {
                continue;
            }
            // Only an invoice originally posted as INVOICE_RAISED_DEFERRED actually carries a
            // 2120 balance to move - a COMMISSION_AGENT invoice (row 3) and an invoice that was
            // already immediately recognized on issue (row 2) have nothing deferred here, and
            // posting against either would debit 2120 with no matching prior credit.
            if (!journalEntryRepository.existsBySourceTypeAndSourceIdAndPurpose(
                    JournalSourceType.INVOICE, invoice.getId(), JournalPurpose.INVOICE_RAISED_DEFERRED)) {
                continue;
            }
            if (recognize(invoice, asOf)) {
                recognized++;
            }
        }
        log.info("Revenue recognition: {} eligible invoice(s), {} recognized", invoices.size(), recognized);
        return new RecognitionResult(invoices.size(), recognized);
    }

    private boolean recognize(Invoice invoice, LocalDate asOf) {
        BigDecimal taxableInr = invoice.getTaxableValueInr();
        if (taxableInr == null || taxableInr.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        InvoiceServiceCategory category = invoice.getServiceCategory() != null
                ? invoice.getServiceCategory() : InvoiceServiceCategory.MISCELLANEOUS;
        String narration = "Revenue recognized on departure for invoice " + invoice.getInvoiceNumber();

        List<JournalLinePosting> lines = List.of(
                JournalLinePosting.debit(SystemAccount.UNEARNED_TOUR_REVENUE.code(), taxableInr, narration),
                JournalLinePosting.credit(SystemAccount.salesCode(category), taxableInr, narration));

        journalService.post(new JournalPosting(
                asOf, JournalSourceType.INVOICE, invoice.getId(), JournalPurpose.REVENUE_RECOGNIZED,
                narration, invoice.getBookingId(), invoice.getBranchId(), lines));
        return true;
    }
}
