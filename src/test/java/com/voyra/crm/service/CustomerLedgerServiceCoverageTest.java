package com.voyra.crm.service;

import com.voyra.crm.dto.ArAgeingRowResponse;
import com.voyra.crm.dto.ClientCreditResponse;
import com.voyra.crm.dto.ClientLedgerSummaryResponse;
import com.voyra.crm.dto.LedgerStatementResponse;
import com.voyra.crm.dto.OpeningBalanceRequest;
import com.voyra.crm.entity.Client;
import com.voyra.crm.entity.CustomerLedgerEntry;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.enums.InvoiceDocumentType;
import com.voyra.crm.enums.InvoiceLifecycle;
import com.voyra.crm.enums.JournalPurpose;
import com.voyra.crm.enums.LedgerEntryType;
import com.voyra.crm.enums.LedgerSourceType;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.models.JournalLinePosting;
import com.voyra.crm.models.JournalPosting;
import com.voyra.crm.repository.ClientRepository;
import com.voyra.crm.repository.CustomerLedgerEntryRepository;
import com.voyra.crm.repository.InvoiceRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Statement ranges and exports, all-client summaries, AR ageing buckets and a credit opening balance. */
@ExtendWith(MockitoExtension.class)
class CustomerLedgerServiceCoverageTest {

    @Mock private CustomerLedgerEntryRepository ledgerRepository;
    @Mock private InvoiceRepository invoiceRepository;
    @Mock private ClientService clientService;
    @Mock private ClientRepository clientRepository;
    @Mock private JournalService journalService;
    @Mock private ClientCreditService clientCreditService;
    @Spy private BaseCurrency baseCurrency = new BaseCurrency(null);
    @InjectMocks private CustomerLedgerService service;

    private final Client client = Client.builder().id("K1").name("Client One").build();
    private static final LocalDate D = LocalDate.of(2026, 9, 1);

    @BeforeEach
    void auth() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("AC1", "acct", UserType.ACCOUNTANT, "T1"), null, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static CustomerLedgerEntry entry(LocalDate date, LedgerEntryType type, String debit, String credit) {
        BigDecimal d = new BigDecimal(debit);
        BigDecimal c = new BigDecimal(credit);
        return CustomerLedgerEntry.builder().id("E-" + type + date).clientId("K1").entryDate(date).entryType(type)
                .sourceType(LedgerSourceType.INVOICE).sourceId("S").narration(type.name())
                .debitAmount(d).creditAmount(c).debitAmountInr(d).creditAmountInr(c).build();
    }

    @Test
    void aStatementWithOnlyAnEndDateRunsFromTheStartAndOneWithOnlyAStartRunsToToday() {
        when(clientService.findAccessibleClient("K1")).thenReturn(client);
        LocalDate to = LocalDate.of(2026, 10, 1);
        when(ledgerRepository.findByClientIdAndEntryDateBetweenOrderByEntryDateAscCreatedAtAsc("K1", LocalDate.of(2000, 1, 1), to))
                .thenReturn(List.of(entry(D, LedgerEntryType.INVOICE_RAISED, "1000", "0")));
        when(ledgerRepository.findByClientIdAndEntryDateLessThanOrderByEntryDateAscCreatedAtAsc("K1", D)).thenReturn(List.of(
                entry(D.minusDays(5), LedgerEntryType.OPENING_BALANCE, "300", "0")));
        when(ledgerRepository.findByClientIdAndEntryDateBetweenOrderByEntryDateAscCreatedAtAsc("K1", D, LocalDate.now()))
                .thenReturn(List.of(entry(D, LedgerEntryType.PAYMENT_RECEIVED, "0", "500")));

        LedgerStatementResponse untilTo = service.statement("K1", null, to);
        assertThat(untilTo.getOpeningBalanceInr()).isEqualByComparingTo("0");
        assertThat(untilTo.getClosingBalanceInr()).isEqualByComparingTo("1000");

        LedgerStatementResponse fromOnly = service.statement("K1", D, null);
        assertThat(fromOnly.getOpeningBalanceInr()).isEqualByComparingTo("300");
        assertThat(fromOnly.getClosingBalanceInr()).isEqualByComparingTo("-200");
        assertThat(fromOnly.getEntries().get(0).getRunningBalanceInr()).isEqualByComparingTo("-200");
    }

    private void fullLedger() {
        when(clientService.findAccessibleClient("K1")).thenReturn(client);
        when(ledgerRepository.findByClientIdOrderByEntryDateAscCreatedAtAsc("K1")).thenReturn(List.of(
                entry(D, LedgerEntryType.INVOICE_RAISED, "1180.00", "0"),
                entry(D.plusDays(1), LedgerEntryType.PAYMENT_RECEIVED, "0", "500.00")));
    }

    @Test
    void theStatementExportsShowDebitsAndCreditsInTheirOwnColumns() throws Exception {
        fullLedger();

        byte[] xlsx = service.exportStatementXlsx("K1", null, null);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet s = wb.getSheetAt(0);
            assertThat(s.getRow(0).getCell(5).getStringCellValue()).isEqualTo("Balance (INR)");
            assertThat(s.getRow(1).getCell(1).getStringCellValue()).isEqualTo("INVOICE_RAISED");
            assertThat(s.getRow(1).getCell(3).getStringCellValue()).isEqualTo("1180.00");
            assertThat(s.getRow(1).getCell(4).getStringCellValue()).isEmpty();
            assertThat(s.getRow(2).getCell(3).getStringCellValue()).isEmpty();
            assertThat(s.getRow(2).getCell(4).getStringCellValue()).isEqualTo("500.00");
            assertThat(s.getRow(2).getCell(5).getStringCellValue()).isEqualTo("680.00");
        }

        byte[] pdf = service.exportStatementPdf("K1", null, null);
        assertThat(new String(pdf, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
    }

    @Test
    void everyClientsSummaryCarriesItsCreditLimitAndAvailable() {
        Client other = Client.builder().id("K2").name("Client Two").build();
        when(clientRepository.findAll()).thenReturn(List.of(client, other));
        when(clientCreditService.creditFor(List.of(client, other))).thenReturn(Map.of(
                "K1", ClientCreditResponse.builder().limit(new BigDecimal("5000")).available(new BigDecimal("4320")).build(),
                "K2", ClientCreditResponse.builder().limit(null).available(null).build()));
        when(clientService.findAccessibleClient("K1")).thenReturn(client);
        when(clientService.findAccessibleClient("K2")).thenReturn(other);
        when(ledgerRepository.findByClientIdOrderByEntryDateAscCreatedAtAsc("K1")).thenReturn(List.of(
                entry(D, LedgerEntryType.INVOICE_RAISED, "1180.00", "0"),
                entry(D, LedgerEntryType.PAYMENT_RECEIVED, "0", "500.00")));
        when(ledgerRepository.findByClientIdOrderByEntryDateAscCreatedAtAsc("K2")).thenReturn(List.of(
                entry(D, LedgerEntryType.PAYMENT_RECEIVED, "0", "250.00"),
                entry(D, LedgerEntryType.REVERSAL, "0", "40.00")));

        List<ClientLedgerSummaryResponse> out = service.summaryForAllClients();

        assertThat(out.get(0).getOutstandingInr()).isEqualByComparingTo("680.00");
        assertThat(out.get(0).getCreditLimit()).isEqualByComparingTo("5000");
        assertThat(out.get(0).getCreditAvailable()).isEqualByComparingTo("4320");
        assertThat(out.get(1).getAdvanceInr()).isEqualByComparingTo("290.00");
        assertThat(out.get(1).getBilledInr()).isEqualByComparingTo("-40.00");
        assertThat(out.get(1).getReceivedInr()).isEqualByComparingTo("250.00");
        assertThat(out.get(1).getOutstandingInr()).isEqualByComparingTo("0");
        assertThat(out.get(1).getCreditLimit()).isNull();
    }

    private static Invoice inv(String client, LocalDate due, LocalDate invoiceDate, String balance) {
        return Invoice.builder().clientId(client).clientName("Name " + client).dueDate(due).invoiceDate(invoiceDate)
                .balanceDueInr(new BigDecimal(balance)).build();
    }

    private void ageingData() {
        LocalDate today = LocalDate.now();
        when(invoiceRepository.findByDocumentTypeAndStatusInAndBalanceDueInrGreaterThan(InvoiceDocumentType.TAX_INVOICE,
                List.of(InvoiceLifecycle.ISSUED, InvoiceLifecycle.PARTIALLY_PAID), BigDecimal.ZERO)).thenReturn(List.of(
                inv("K1", today.plusDays(3), null, "100"),
                inv("K1", null, today.minusDays(30), "200"),
                inv("K1", today.minusDays(31), null, "300"),
                inv("K1", today.minusDays(60), null, "400"),
                inv("K1", today.minusDays(61), null, "500"),
                inv("K1", today.minusDays(90), null, "600"),
                inv("K1", today.minusDays(91), null, "700"),
                inv("K2", null, null, "50")));
    }

    @Test
    void ageingBucketsByDueDateFallingBackToTheInvoiceDateAndCountsUndatedAsCurrent() {
        ageingData();

        List<ArAgeingRowResponse> out = service.outstanding();

        ArAgeingRowResponse k1 = out.get(0);
        assertThat(k1.getClientName()).isEqualTo("Name K1");
        assertThat(k1.getCurrent()).isEqualByComparingTo("100");
        assertThat(k1.getDays1To30()).isEqualByComparingTo("200");
        assertThat(k1.getDays31To60()).isEqualByComparingTo("700");
        assertThat(k1.getDays61To90()).isEqualByComparingTo("1100");
        assertThat(k1.getDays90Plus()).isEqualByComparingTo("700");
        assertThat(k1.getTotalOutstandingInr()).isEqualByComparingTo("2800");
        assertThat(out.get(1).getCurrent()).isEqualByComparingTo("50");
    }

    @Test
    void ageingExportsOneRowPerClient() throws Exception {
        ageingData();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(service.exportOutstandingXlsx()))) {
            Sheet s = wb.getSheetAt(0);
            assertThat(s.getRow(0).getCell(6).getStringCellValue()).isEqualTo("Total (INR)");
            assertThat(s.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Name K1");
            assertThat(s.getRow(1).getCell(6).getStringCellValue()).isEqualTo("2800");
            assertThat(s.getRow(2).getCell(1).getStringCellValue()).isEqualTo("50");
        }
    }

    @Test
    void aNegativeOpeningBalanceIsAnAdvanceCreditedToReceivablesAgainstEquity() {
        when(clientService.findAccessibleClient("K1")).thenReturn(client);
        OpeningBalanceRequest r = new OpeningBalanceRequest();
        r.setAmount(new BigDecimal("-750.00"));
        r.setAsOfDate(D);
        r.setNote("  ");

        service.openingBalance("K1", r);

        ArgumentCaptor<CustomerLedgerEntry> row = ArgumentCaptor.forClass(CustomerLedgerEntry.class);
        verify(ledgerRepository).save(row.capture());
        CustomerLedgerEntry e = row.getValue();
        assertThat(e.getEntryType()).isEqualTo(LedgerEntryType.OPENING_BALANCE);
        assertThat(e.getNarration()).isEqualTo("Opening balance");
        assertThat(e.getDocumentNumber()).isEqualTo("OB-2026-09-01");
        assertThat(e.getSourceId()).isEqualTo("K1");
        assertThat(e.getDebitAmountInr()).isEqualByComparingTo("0");
        assertThat(e.getCreditAmountInr()).isEqualByComparingTo("750.00");
        assertThat(e.getCreditAmount()).isEqualByComparingTo("750.00");
        ArgumentCaptor<JournalPosting> j = ArgumentCaptor.forClass(JournalPosting.class);
        verify(journalService).post(j.capture());
        assertThat(j.getValue().purpose()).isEqualTo(JournalPurpose.OPENING_BALANCE_POSTED);
        assertThat(j.getValue().lines()).extracting(JournalLinePosting::accountCode).containsExactly("3100", "1200");
        assertThat(j.getValue().lines().get(0).debitAmount()).isEqualByComparingTo("750.00");
        assertThat(j.getValue().lines().get(1).creditAmount()).isEqualByComparingTo("750.00");
        assertThat(j.getValue().lines().get(1).partyId()).isEqualTo("K1");
        verify(ledgerRepository, never()).findByClientIdAndEntryDateBetweenOrderByEntryDateAscCreatedAtAsc(any(), any(), any());
    }

    @Test
    void anOpeningBalanceKeepsItsNote() {
        when(clientService.findAccessibleClient("K1")).thenReturn(client);
        OpeningBalanceRequest r = new OpeningBalanceRequest();
        r.setAmount(new BigDecimal("100"));
        r.setAsOfDate(D);
        r.setNote("Carried over from Tally");

        service.openingBalance("K1", r);

        ArgumentCaptor<CustomerLedgerEntry> row = ArgumentCaptor.forClass(CustomerLedgerEntry.class);
        verify(ledgerRepository).save(row.capture());
        assertThat(row.getValue().getNarration()).isEqualTo("Carried over from Tally");
    }
}
