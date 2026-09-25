package com.voyra.crm.service;

import com.voyra.crm.dto.ApAgeingRowResponse;
import com.voyra.crm.dto.SupplierLedgerEntryResponse;
import com.voyra.crm.dto.SupplierLedgerStatementResponse;
import com.voyra.crm.dto.SupplierOpeningBalanceRequest;
import com.voyra.crm.dto.VendorLedgerSummaryResponse;
import com.voyra.crm.entity.SupplierInvoice;
import com.voyra.crm.entity.SupplierLedgerEntry;
import com.voyra.crm.entity.Vendor;
import com.voyra.crm.enums.SupplierInvoiceStatus;
import com.voyra.crm.enums.SupplierLedgerEntryType;
import com.voyra.crm.enums.SupplierLedgerSourceType;
import com.voyra.crm.models.SupplierLedgerPosting;
import com.voyra.crm.repository.SupplierInvoiceRepository;
import com.voyra.crm.repository.SupplierLedgerEntryRepository;
import com.voyra.crm.repository.VendorRepository;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.PdfTableWriter;
import com.voyra.crm.util.ReportTable;
import com.voyra.crm.util.UniqueIdResolver;
import com.voyra.crm.util.XlsxWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The sole writer of {@link SupplierLedgerEntry} rows - the accounts-payable mirror of
 * {@code CustomerLedgerService}. {@link #post} is called by {@code SupplierInvoiceService}
 * (approve, cancel), {@code SupplierPaymentService} (pay, reverse) and
 * {@code SupplierCreditNoteService} (record, cancel, refund) inside their own existing
 * transaction. The unique index on {@code (source_type, source_id, entry_type)} is the only
 * duplicate-post guard.
 *
 * <p><b>Sign convention (ARCHITECTURE-SPINE AD-4):</b> CREDIT raises what we owe the vendor
 * (a bill booked, a credit note reversed); DEBIT lowers it (a payment made, an advance paid, a
 * credit note received). {@code balance = SUM(credit_inr) - SUM(debit_inr)}: positive means we
 * owe the vendor, negative means the vendor holds our deposit.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SupplierLedgerService {

    private final SupplierLedgerEntryRepository ledgerRepository;
    private final SupplierInvoiceRepository supplierInvoiceRepository;
    private final VendorRepository vendorRepository;

    @Transactional
    public void post(SupplierLedgerPosting posting) {
        SupplierLedgerEntry entry = SupplierLedgerEntry.builder()
                .id(UniqueIdResolver.resolve(ledgerRepository::existsById))
                .vendorId(posting.vendorId())
                .entryDate(posting.entryDate())
                .entryType(posting.entryType())
                .sourceType(posting.sourceType())
                .sourceId(posting.sourceId())
                .documentNumber(posting.documentNumber())
                .narration(posting.narration())
                .bookingId(posting.bookingId())
                .currencyCode(posting.currencyCode())
                .fxRateToInr(posting.fxRateToInr())
                .debitAmount(posting.debitAmount())
                .creditAmount(posting.creditAmount())
                .debitAmountInr(posting.debitAmountInr())
                .creditAmountInr(posting.creditAmountInr())
                .createdAt(LocalDateTime.now())
                .createdBy(SecurityContextUtil.getCurrentUserOrThrow().userId())
                .build();
        ledgerRepository.save(entry);
        log.info("Supplier ledger posted: vendorId={}, type={}, sourceType={}, sourceId={}",
                entry.getVendorId(), entry.getEntryType(), entry.getSourceType(), entry.getSourceId());
    }

    /** Running balance computed over an ordered fetch, never stored - mirrors CustomerLedgerService#statement. */
    @Transactional(readOnly = true)
    public SupplierLedgerStatementResponse statement(String vendorId, LocalDate from, LocalDate to) {
        Vendor vendor = findVendor(vendorId);

        List<SupplierLedgerEntry> before = from == null ? List.of()
                : ledgerRepository.findByVendorIdAndEntryDateLessThanOrderByEntryDateAscCreatedAtAsc(vendorId, from);
        BigDecimal opening = runningTotal(before);

        List<SupplierLedgerEntry> inRange = (from == null && to == null)
                ? ledgerRepository.findByVendorIdOrderByEntryDateAscCreatedAtAsc(vendorId)
                : ledgerRepository.findByVendorIdAndEntryDateBetweenOrderByEntryDateAscCreatedAtAsc(
                        vendorId,
                        from != null ? from : LocalDate.of(2000, 1, 1),
                        to != null ? to : LocalDate.now());

        BigDecimal running = opening;
        List<SupplierLedgerEntryResponse> rows = new ArrayList<>();
        for (SupplierLedgerEntry e : inRange) {
            running = running.add(e.getCreditAmountInr()).subtract(e.getDebitAmountInr());
            rows.add(toResponse(e, running));
        }

        return SupplierLedgerStatementResponse.builder()
                .vendorId(vendor.getId()).vendorName(vendor.getName())
                .from(from).to(to)
                .openingBalanceInr(opening)
                .closingBalanceInr(running)
                .entries(rows)
                .build();
    }

    @Transactional(readOnly = true)
    public byte[] exportStatementXlsx(String vendorId, LocalDate from, LocalDate to) {
        return XlsxWriter.write("Vendor Statement of Account", statementTable(vendorId, from, to));
    }

    @Transactional(readOnly = true)
    public byte[] exportStatementPdf(String vendorId, LocalDate from, LocalDate to) {
        SupplierLedgerStatementResponse s = statement(vendorId, from, to);
        return PdfTableWriter.write("Vendor Statement of Account - " + s.getVendorName(), statementTable(s));
    }

    private ReportTable statementTable(String vendorId, LocalDate from, LocalDate to) {
        return statementTable(statement(vendorId, from, to));
    }

    private ReportTable statementTable(SupplierLedgerStatementResponse s) {
        List<String> header = List.of("Date", "Type", "Narration", "Debit (INR)", "Credit (INR)", "Balance (INR)");
        List<List<String>> rows = s.getEntries().stream()
                .map(e -> List.of(
                        e.getEntryDate().toString(), e.getEntryType().name(), e.getNarration(),
                        e.getDebitAmountInr().compareTo(BigDecimal.ZERO) > 0 ? e.getDebitAmountInr().toString() : "",
                        e.getCreditAmountInr().compareTo(BigDecimal.ZERO) > 0 ? e.getCreditAmountInr().toString() : "",
                        e.getRunningBalanceInr().toString()))
                .toList();
        return new ReportTable(header, rows);
    }

    @Transactional(readOnly = true)
    public VendorLedgerSummaryResponse summary(String vendorId) {
        Vendor vendor = findVendor(vendorId);
        List<SupplierLedgerEntry> entries = ledgerRepository.findByVendorIdOrderByEntryDateAscCreatedAtAsc(vendorId);

        BigDecimal billed = entries.stream()
                .filter(e -> e.getEntryType() == SupplierLedgerEntryType.BILL_BOOKED || e.getEntryType() == SupplierLedgerEntryType.REVERSAL)
                .map(e -> e.getCreditAmountInr().subtract(e.getDebitAmountInr()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal paid = entries.stream()
                .filter(e -> e.getEntryType() == SupplierLedgerEntryType.PAYMENT_MADE)
                .map(e -> e.getDebitAmountInr().subtract(e.getCreditAmountInr()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal running = runningTotal(entries);

        return VendorLedgerSummaryResponse.builder()
                .vendorId(vendor.getId()).vendorName(vendor.getName())
                .creditLimitInr(vendor.getCreditLimitInr())
                .billedInr(billed).paidInr(paid)
                .payableInr(running.max(BigDecimal.ZERO))
                .advanceInr(running.min(BigDecimal.ZERO).negate())
                .build();
    }

    /** Every vendor's roll-up - the Payables console's Suppliers screen. */
    @Transactional(readOnly = true)
    public List<VendorLedgerSummaryResponse> summaryForAllVendors() {
        return vendorRepository.findAllByOrderBySortOrderAscNameAsc().stream()
                .map(v -> summary(v.getId())).toList();
    }

    /** InvoiceService#getSummary's Owner-only "paid to suppliers" figure. */
    @Transactional(readOnly = true)
    public BigDecimal totalPaidAllVendors() {
        return summaryForAllVendors().stream().map(VendorLedgerSummaryResponse::getPaidInr)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** InvoiceService#getSummary's Owner-only "pending to pay" figure. */
    @Transactional(readOnly = true)
    public BigDecimal totalPayableAllVendors() {
        return summaryForAllVendors().stream().map(VendorLedgerSummaryResponse::getPayableInr)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** AP ageing across every vendor with an unpaid approved bill, bucketed by days past due. */
    @Transactional(readOnly = true)
    public List<ApAgeingRowResponse> outstanding() {
        List<SupplierInvoice> unpaid = supplierInvoiceRepository.findAll().stream()
                .filter(i -> (i.getStatus() == SupplierInvoiceStatus.APPROVED || i.getStatus() == SupplierInvoiceStatus.PARTIALLY_PAID)
                        && i.getBalanceDueInr().compareTo(BigDecimal.ZERO) > 0)
                .toList();

        LocalDate today = LocalDate.now();
        Map<String, String> namesByVendor = new LinkedHashMap<>();
        Map<String, BigDecimal[]> buckets = new LinkedHashMap<>();

        for (SupplierInvoice inv : unpaid) {
            if (inv.getVendorId() == null) {
                continue;
            }
            namesByVendor.putIfAbsent(inv.getVendorId(), inv.getVendorName());
            BigDecimal[] row = buckets.computeIfAbsent(inv.getVendorId(), id -> new BigDecimal[]{
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});

            LocalDate reference = inv.getDueDate() != null ? inv.getDueDate() : inv.getInvoiceDate();
            long daysOverdue = reference == null ? 0 : ChronoUnit.DAYS.between(reference, today);
            int bucket = daysOverdue <= 0 ? 0 : daysOverdue <= 30 ? 1 : daysOverdue <= 60 ? 2 : daysOverdue <= 90 ? 3 : 4;
            row[bucket] = row[bucket].add(inv.getBalanceDueInr());
        }

        return buckets.entrySet().stream()
                .map(e -> {
                    BigDecimal[] b = e.getValue();
                    return ApAgeingRowResponse.builder()
                            .vendorId(e.getKey()).vendorName(namesByVendor.get(e.getKey()))
                            .current(b[0]).days1To30(b[1]).days31To60(b[2]).days61To90(b[3]).days90Plus(b[4])
                            .totalPayableInr(b[0].add(b[1]).add(b[2]).add(b[3]).add(b[4]))
                            .build();
                })
                .toList();
    }

    @Transactional
    public SupplierLedgerStatementResponse openingBalance(String vendorId, SupplierOpeningBalanceRequest request) {
        Vendor vendor = findVendor(vendorId);
        BigDecimal amount = request.getAmount();
        boolean isCredit = amount.compareTo(BigDecimal.ZERO) >= 0;
        BigDecimal magnitude = amount.abs();
        String note = request.getNote() != null && !request.getNote().isBlank() ? request.getNote() : "Opening balance";

        post(new SupplierLedgerPosting(
                vendor.getId(), request.getAsOfDate(), SupplierLedgerEntryType.OPENING_BALANCE, SupplierLedgerSourceType.OPENING_BALANCE,
                // sourceId is the (source_type, source_id, entry_type) idempotency key and is
                // only 36 chars wide - it must be the bare vendor id, not a longer composite
                // string, or every opening balance overflows the column (F-001, mirrors the
                // identical bug in CustomerLedgerService). documentNumber carries the label instead.
                vendor.getId(), "OB-" + request.getAsOfDate(), note, null,
                "INR", BigDecimal.ONE,
                isCredit ? BigDecimal.ZERO : magnitude, isCredit ? magnitude : BigDecimal.ZERO,
                isCredit ? BigDecimal.ZERO : magnitude, isCredit ? magnitude : BigDecimal.ZERO));

        return statement(vendorId, null, null);
    }

    private static BigDecimal runningTotal(List<SupplierLedgerEntry> entries) {
        BigDecimal total = BigDecimal.ZERO;
        for (SupplierLedgerEntry e : entries) {
            total = total.add(e.getCreditAmountInr()).subtract(e.getDebitAmountInr());
        }
        return total;
    }

    private Vendor findVendor(String vendorId) {
        return vendorRepository.findById(vendorId)
                .orElseThrow(() -> new IllegalArgumentException("Vendor not found: " + vendorId));
    }

    private static SupplierLedgerEntryResponse toResponse(SupplierLedgerEntry e, BigDecimal runningBalanceInr) {
        return SupplierLedgerEntryResponse.builder()
                .id(e.getId()).entryDate(e.getEntryDate()).entryType(e.getEntryType())
                .sourceType(e.getSourceType()).sourceId(e.getSourceId()).documentNumber(e.getDocumentNumber())
                .narration(e.getNarration()).bookingId(e.getBookingId())
                .currencyCode(e.getCurrencyCode()).fxRateToInr(e.getFxRateToInr())
                .debitAmount(e.getDebitAmount()).creditAmount(e.getCreditAmount())
                .debitAmountInr(e.getDebitAmountInr()).creditAmountInr(e.getCreditAmountInr())
                .runningBalanceInr(runningBalanceInr)
                .createdAt(e.getCreatedAt())
                .build();
    }
}
