package com.voyra.crm.service;

import com.voyra.crm.dto.ArAgeingRowResponse;
import com.voyra.crm.dto.ClientLedgerSummaryResponse;
import com.voyra.crm.dto.LedgerEntryResponse;
import com.voyra.crm.dto.LedgerStatementResponse;
import com.voyra.crm.dto.OpeningBalanceRequest;
import com.voyra.crm.entity.Client;
import com.voyra.crm.entity.CustomerLedgerEntry;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.enums.InvoiceDocumentType;
import com.voyra.crm.enums.InvoiceLifecycle;
import com.voyra.crm.enums.LedgerEntryType;
import com.voyra.crm.enums.LedgerSourceType;
import com.voyra.crm.models.LedgerPosting;
import com.voyra.crm.repository.ClientRepository;
import com.voyra.crm.repository.CustomerLedgerEntryRepository;
import com.voyra.crm.repository.InvoiceRepository;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The sole writer of {@link CustomerLedgerEntry} rows. {@link #post} is called by
 * {@code InvoiceDocumentService} (issue, convert-to-tax-invoice, cancel) and
 * {@code PaymentReceiptService} (record, reverse) inside their own existing transaction - a
 * rollback there drops the ledger row with it. The unique index on
 * {@code (source_type, source_id, entry_type)} is the only duplicate-post guard; this class adds
 * no in-code check on top of it, per the architecture note that a double-post should surface as a
 * constraint violation, not be silently absorbed.
 *
 * <p><b>Invariant this class must keep true:</b> for any client,
 * {@code SUM(debit_amount_inr - credit_amount_inr)} over their ledger equals
 * {@code SUM(invoice.balance_due_inr)} over their non-cancelled tax invoices, plus any opening
 * balance. Every posting site is written to preserve this - see each call site's own comment.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CustomerLedgerService {

    private final CustomerLedgerEntryRepository ledgerRepository;
    private final InvoiceRepository invoiceRepository;
    private final ClientService clientService;
    private final ClientRepository clientRepository;

    @Transactional
    public void post(LedgerPosting posting) {
        CustomerLedgerEntry entry = CustomerLedgerEntry.builder()
                .id(UniqueIdResolver.resolve(ledgerRepository::existsById))
                .clientId(posting.clientId())
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
                .createdAt(java.time.LocalDateTime.now())
                .createdBy(SecurityContextUtil.getCurrentUserOrThrow().userId())
                .build();
        ledgerRepository.save(entry);
        log.info("Ledger posted: clientId={}, type={}, sourceType={}, sourceId={}",
                entry.getClientId(), entry.getEntryType(), entry.getSourceType(), entry.getSourceId());
    }

    /**
     * The running balance is computed here, over an ordered fetch, rather than stored - a stored
     * {@code running_balance} column would serialise every write for a client on one row and,
     * more importantly, could drift from the rows it summarises. A per-client ledger is small
     * enough that this needs no pagination or SQL window function.
     */
    @Transactional(readOnly = true)
    public LedgerStatementResponse statement(String clientId, LocalDate from, LocalDate to) {
        Client client = clientService.findAccessibleClient(clientId);

        List<CustomerLedgerEntry> before = from == null ? List.of()
                : ledgerRepository.findByClientIdAndEntryDateLessThanOrderByEntryDateAscCreatedAtAsc(clientId, from);
        BigDecimal opening = runningTotal(before);

        List<CustomerLedgerEntry> inRange = (from == null && to == null)
                ? ledgerRepository.findByClientIdOrderByEntryDateAscCreatedAtAsc(clientId)
                : ledgerRepository.findByClientIdAndEntryDateBetweenOrderByEntryDateAscCreatedAtAsc(
                        clientId,
                        from != null ? from : LocalDate.of(2000, 1, 1),
                        to != null ? to : LocalDate.now());

        BigDecimal running = opening;
        List<LedgerEntryResponse> rows = new ArrayList<>();
        for (CustomerLedgerEntry e : inRange) {
            running = running.add(e.getDebitAmountInr()).subtract(e.getCreditAmountInr());
            rows.add(toResponse(e, running));
        }

        return LedgerStatementResponse.builder()
                .clientId(client.getId()).clientName(client.getName())
                .from(from).to(to)
                .openingBalanceInr(opening)
                .closingBalanceInr(running)
                .entries(rows)
                .build();
    }

    /** FR6: "The statement exports to PDF and Excel" - same {@link ReportTable} feeding both writers, per the pattern {@code ReportService} already uses for every other export. */
    @Transactional(readOnly = true)
    public byte[] exportStatementXlsx(String clientId, LocalDate from, LocalDate to) {
        return XlsxWriter.write("Statement of Account", statementTable(clientId, from, to));
    }

    @Transactional(readOnly = true)
    public byte[] exportStatementPdf(String clientId, LocalDate from, LocalDate to) {
        LedgerStatementResponse s = statement(clientId, from, to);
        return PdfTableWriter.write("Statement of Account - " + s.getClientName(), statementTable(s));
    }

    private ReportTable statementTable(String clientId, LocalDate from, LocalDate to) {
        return statementTable(statement(clientId, from, to));
    }

    private ReportTable statementTable(LedgerStatementResponse s) {
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
    public ClientLedgerSummaryResponse summary(String clientId) {
        Client client = clientService.findAccessibleClient(clientId);
        List<CustomerLedgerEntry> entries = ledgerRepository.findByClientIdOrderByEntryDateAscCreatedAtAsc(clientId);

        BigDecimal billed = entries.stream()
                .filter(e -> e.getEntryType() == LedgerEntryType.INVOICE_RAISED || e.getEntryType() == LedgerEntryType.REVERSAL)
                .map(e -> e.getDebitAmountInr().subtract(e.getCreditAmountInr()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal received = entries.stream()
                .filter(e -> e.getEntryType() == LedgerEntryType.PAYMENT_RECEIVED)
                .map(e -> e.getCreditAmountInr().subtract(e.getDebitAmountInr()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal running = runningTotal(entries);

        return ClientLedgerSummaryResponse.builder()
                .clientId(client.getId()).clientName(client.getName())
                .billedInr(billed).receivedInr(received)
                .outstandingInr(running.max(BigDecimal.ZERO))
                .advanceInr(running.min(BigDecimal.ZERO).negate())
                .build();
    }

    /**
     * The billed/received/outstanding/advance roll-up for every client in the tenant - the
     * Accounts console's own Customers screen. Deliberately goes through {@link ClientRepository}
     * directly rather than {@code ClientController}/{@code /api/clients}, which is Owner/Agent
     * only: an Accountant has no CRM access (FR1), but must still see every client's financial
     * position here.
     */
    @Transactional(readOnly = true)
    public List<ClientLedgerSummaryResponse> summaryForAllClients() {
        return clientRepository.findAll().stream().map(c -> summary(c.getId())).toList();
    }

    /** AR ageing across every client with an unpaid tax invoice, bucketed by days past the due date (or invoice date, if none was set). */
    @Transactional(readOnly = true)
    public List<ArAgeingRowResponse> outstanding() {
        List<Invoice> unpaid = invoiceRepository.findByDocumentTypeAndStatusInAndBalanceDueInrGreaterThan(
                InvoiceDocumentType.TAX_INVOICE,
                List.of(InvoiceLifecycle.ISSUED, InvoiceLifecycle.PARTIALLY_PAID),
                BigDecimal.ZERO);

        LocalDate today = LocalDate.now();
        Map<String, String> namesByClient = new LinkedHashMap<>();
        Map<String, BigDecimal[]> buckets = new LinkedHashMap<>();

        for (Invoice inv : unpaid) {
            namesByClient.putIfAbsent(inv.getClientId(), inv.getClientName());
            BigDecimal[] row = buckets.computeIfAbsent(inv.getClientId(), id -> new BigDecimal[]{
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});

            LocalDate reference = inv.getDueDate() != null ? inv.getDueDate() : inv.getInvoiceDate();
            long daysOverdue = reference == null ? 0 : ChronoUnit.DAYS.between(reference, today);
            int bucket = daysOverdue <= 0 ? 0 : daysOverdue <= 30 ? 1 : daysOverdue <= 60 ? 2 : daysOverdue <= 90 ? 3 : 4;
            row[bucket] = row[bucket].add(inv.getBalanceDueInr());
        }

        return buckets.entrySet().stream()
                .map(e -> {
                    BigDecimal[] b = e.getValue();
                    return ArAgeingRowResponse.builder()
                            .clientId(e.getKey()).clientName(namesByClient.get(e.getKey()))
                            .current(b[0]).days1To30(b[1]).days31To60(b[2]).days61To90(b[3]).days90Plus(b[4])
                            .totalOutstandingInr(b[0].add(b[1]).add(b[2]).add(b[3]).add(b[4]))
                            .build();
                })
                .toList();
    }

    /** Posts one OPENING_BALANCE row so a client onboarded mid-relationship starts with a correct balance. */
    @Transactional
    public LedgerStatementResponse openingBalance(String clientId, OpeningBalanceRequest request) {
        Client client = clientService.findAccessibleClient(clientId);
        BigDecimal amount = request.getAmount();
        boolean isDebit = amount.compareTo(BigDecimal.ZERO) >= 0;
        BigDecimal magnitude = amount.abs();
        String note = request.getNote() != null && !request.getNote().isBlank() ? request.getNote() : "Opening balance";

        post(new LedgerPosting(
                client.getId(), request.getAsOfDate(), LedgerEntryType.OPENING_BALANCE, LedgerSourceType.OPENING_BALANCE,
                "OB-" + client.getId() + "-" + request.getAsOfDate(), null, note, null,
                "INR", BigDecimal.ONE,
                isDebit ? magnitude : BigDecimal.ZERO, isDebit ? BigDecimal.ZERO : magnitude,
                isDebit ? magnitude : BigDecimal.ZERO, isDebit ? BigDecimal.ZERO : magnitude));

        return statement(clientId, null, null);
    }

    private static BigDecimal runningTotal(List<CustomerLedgerEntry> entries) {
        BigDecimal total = BigDecimal.ZERO;
        for (CustomerLedgerEntry e : entries) {
            total = total.add(e.getDebitAmountInr()).subtract(e.getCreditAmountInr());
        }
        return total;
    }

    private static LedgerEntryResponse toResponse(CustomerLedgerEntry e, BigDecimal runningBalanceInr) {
        return LedgerEntryResponse.builder()
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
