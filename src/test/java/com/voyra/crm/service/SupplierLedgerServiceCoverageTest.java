package com.voyra.crm.service;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.voyra.crm.dto.ApAgeingRowResponse;
import com.voyra.crm.dto.SupplierLedgerStatementResponse;
import com.voyra.crm.dto.SupplierOpeningBalanceRequest;
import com.voyra.crm.dto.VendorLedgerSummaryResponse;
import com.voyra.crm.entity.SupplierInvoice;
import com.voyra.crm.entity.SupplierLedgerEntry;
import com.voyra.crm.entity.Vendor;
import com.voyra.crm.enums.JournalPurpose;
import com.voyra.crm.enums.JournalSourceType;
import com.voyra.crm.enums.SupplierInvoiceStatus;
import com.voyra.crm.enums.SupplierLedgerEntryType;
import com.voyra.crm.enums.SupplierLedgerSourceType;
import com.voyra.crm.enums.SystemAccount;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.models.JournalLinePosting;
import com.voyra.crm.models.JournalPosting;
import com.voyra.crm.models.SupplierLedgerPosting;
import com.voyra.crm.repository.SupplierInvoiceRepository;
import com.voyra.crm.repository.SupplierLedgerEntryRepository;
import com.voyra.crm.repository.VendorRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.apache.poi.ss.usermodel.Row;
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
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupplierLedgerServiceCoverageTest {

    @Mock private SupplierLedgerEntryRepository ledgerRepository;
    @Mock private SupplierInvoiceRepository supplierInvoiceRepository;
    @Mock private VendorRepository vendorRepository;
    @Mock private JournalService journalService;
    @Spy private BaseCurrency baseCurrency = new BaseCurrency(null);

    @InjectMocks private SupplierLedgerService service;

    private static final LocalDate D = LocalDate.of(2026, 9, 1);

    @BeforeEach
    void signIn() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("O1", "owner", UserType.AGENCY_OWNER, "T1"), null, List.of()));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private void vendor() {
        when(vendorRepository.findById("V1")).thenReturn(java.util.Optional.of(
                Vendor.builder().id("V1").name("Air Co").creditLimitInr(new BigDecimal("50000")).build()));
    }

    private static SupplierLedgerEntry entry(SupplierLedgerEntryType type, String credit, String debit, LocalDate date) {
        return SupplierLedgerEntry.builder().id(type.name()).vendorId("V1").entryType(type).entryDate(date).narration(type.name())
                .creditAmountInr(new BigDecimal(credit)).debitAmountInr(new BigDecimal(debit)).build();
    }

    @Test
    void aPostingBecomesOneLedgerRowStampedWithItsAuthor() {
        service.post(new SupplierLedgerPosting("V1", D, SupplierLedgerEntryType.BILL_BOOKED, SupplierLedgerSourceType.values()[0],
                "SI1", "BILL-1", "Bill booked", "B1", "USD", new BigDecimal("83"), BigDecimal.ZERO, new BigDecimal("10"),
                BigDecimal.ZERO, new BigDecimal("830")));

        ArgumentCaptor<SupplierLedgerEntry> saved = ArgumentCaptor.forClass(SupplierLedgerEntry.class);
        verify(ledgerRepository).save(saved.capture());
        SupplierLedgerEntry e = saved.getValue();
        assertThat(e.getId()).isNotBlank();
        assertThat(e.getVendorId()).isEqualTo("V1");
        assertThat(e.getSourceId()).isEqualTo("SI1");
        assertThat(e.getDocumentNumber()).isEqualTo("BILL-1");
        assertThat(e.getBookingId()).isEqualTo("B1");
        assertThat(e.getCurrencyCode()).isEqualTo("USD");
        assertThat(e.getCreditAmount()).isEqualByComparingTo("10");
        assertThat(e.getCreditAmountInr()).isEqualByComparingTo("830");
        assertThat(e.getCreatedBy()).isEqualTo("O1");
        assertThat(e.getCreatedAt()).isNotNull();
    }

    @Test
    void anOpenStatementRunsTheBalanceOverEveryEntry() {
        vendor();
        when(ledgerRepository.findByVendorIdOrderByEntryDateAscCreatedAtAsc("V1")).thenReturn(List.of(
                entry(SupplierLedgerEntryType.BILL_BOOKED, "1000", "0", D),
                entry(SupplierLedgerEntryType.PAYMENT_MADE, "0", "400", D.plusDays(1))));

        SupplierLedgerStatementResponse s = service.statement("V1", null, null);

        assertThat(s.getVendorName()).isEqualTo("Air Co");
        assertThat(s.getOpeningBalanceInr()).isEqualByComparingTo("0");
        assertThat(s.getClosingBalanceInr()).isEqualByComparingTo("600");
        assertThat(s.getEntries()).extracting(e -> e.getRunningBalanceInr().toPlainString()).containsExactly("1000", "600");
        assertThat(s.getEntries().get(0).getEntryType()).isEqualTo(SupplierLedgerEntryType.BILL_BOOKED);
        verify(ledgerRepository, never()).findByVendorIdAndEntryDateLessThanOrderByEntryDateAscCreatedAtAsc(any(), any());
    }

    @Test
    void aStatementFromADateOpensWithWhatCameBefore() {
        vendor();
        when(ledgerRepository.findByVendorIdAndEntryDateLessThanOrderByEntryDateAscCreatedAtAsc("V1", D))
                .thenReturn(List.of(entry(SupplierLedgerEntryType.OPENING_BALANCE, "500", "0", D.minusDays(3))));
        when(ledgerRepository.findByVendorIdAndEntryDateBetweenOrderByEntryDateAscCreatedAtAsc(eq("V1"), eq(D), eq(LocalDate.now())))
                .thenReturn(List.of(entry(SupplierLedgerEntryType.PAYMENT_MADE, "0", "200", D)));

        SupplierLedgerStatementResponse s = service.statement("V1", D, null);

        assertThat(s.getFrom()).isEqualTo(D);
        assertThat(s.getOpeningBalanceInr()).isEqualByComparingTo("500");
        assertThat(s.getClosingBalanceInr()).isEqualByComparingTo("300");
    }

    @Test
    void aStatementToADateStartsAtTheBeginningOfTime() {
        vendor();
        when(ledgerRepository.findByVendorIdAndEntryDateBetweenOrderByEntryDateAscCreatedAtAsc("V1", LocalDate.of(2000, 1, 1), D))
                .thenReturn(List.of());

        SupplierLedgerStatementResponse s = service.statement("V1", null, D);

        assertThat(s.getEntries()).isEmpty();
        assertThat(s.getClosingBalanceInr()).isEqualByComparingTo("0");
    }

    @Test
    void anUnknownVendorHasNoStatement() {
        assertThatThrownBy(() -> service.statement("V9", null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Vendor not found: V9");
    }

    @Test
    void theStatementSpreadsheetLeavesTheEmptySideBlank() throws Exception {
        vendor();
        when(ledgerRepository.findByVendorIdOrderByEntryDateAscCreatedAtAsc("V1")).thenReturn(List.of(
                entry(SupplierLedgerEntryType.BILL_BOOKED, "1000", "0", D),
                entry(SupplierLedgerEntryType.PAYMENT_MADE, "0", "400", D)));

        byte[] xlsx = service.exportStatementXlsx("V1", null, null);

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Date");
            Row bill = sheet.getRow(1);
            assertThat(bill.getCell(1).getStringCellValue()).isEqualTo("BILL_BOOKED");
            assertThat(cell(bill, 3)).isEmpty();
            assertThat(cell(bill, 4)).isEqualTo("1000");
            Row paid = sheet.getRow(2);
            assertThat(cell(paid, 3)).isEqualTo("400");
            assertThat(cell(paid, 4)).isEmpty();
            assertThat(cell(paid, 5)).isEqualTo("600");
        }
    }

    private static String cell(Row row, int i) {
        var c = row.getCell(i);
        if (c == null) return "";
        return c.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC
                ? new BigDecimal(String.valueOf(c.getNumericCellValue())).stripTrailingZeros().toPlainString()
                : c.getStringCellValue();
    }

    @Test
    void theStatementPdfIsTitledWithTheVendor() throws Exception {
        vendor();
        when(ledgerRepository.findByVendorIdOrderByEntryDateAscCreatedAtAsc("V1"))
                .thenReturn(List.of(entry(SupplierLedgerEntryType.BILL_BOOKED, "1000", "0", D)));

        byte[] pdf = service.exportStatementPdf("V1", null, null);

        String text = new PdfTextExtractor(new PdfReader(pdf)).getTextFromPage(1);
        assertThat(text).contains("Vendor Statement of Account - Air Co").contains("BILL_BOOKED");
    }

    @Test
    void theSummarySplitsBilledPaidAndTheBalance() {
        vendor();
        when(ledgerRepository.findByVendorIdOrderByEntryDateAscCreatedAtAsc("V1")).thenReturn(List.of(
                entry(SupplierLedgerEntryType.BILL_BOOKED, "1000", "0", D),
                entry(SupplierLedgerEntryType.REVERSAL, "0", "100", D),
                entry(SupplierLedgerEntryType.COMMISSION_RECALLED, "50", "0", D),
                entry(SupplierLedgerEntryType.PAYMENT_MADE, "0", "300", D),
                entry(SupplierLedgerEntryType.ADVANCE_PAID, "0", "900", D),
                entry(SupplierLedgerEntryType.CREDIT_NOTE_RECEIVED, "0", "20", D)));

        VendorLedgerSummaryResponse s = service.summary("V1");

        assertThat(s.getBilledInr()).isEqualByComparingTo("950");
        assertThat(s.getPaidInr()).isEqualByComparingTo("1200");
        assertThat(s.getPayableInr()).isEqualByComparingTo("0");
        assertThat(s.getAdvanceInr()).isEqualByComparingTo("270");
        assertThat(s.getCreditLimitInr()).isEqualByComparingTo("50000");
    }

    @Test
    void theAllVendorTotalsAddEachVendorsSummary() {
        when(vendorRepository.findAllByOrderBySortOrderAscNameAsc()).thenReturn(List.of(
                Vendor.builder().id("V1").build(), Vendor.builder().id("V2").build()));
        when(vendorRepository.findById("V1")).thenReturn(java.util.Optional.of(Vendor.builder().id("V1").name("A").build()));
        when(vendorRepository.findById("V2")).thenReturn(java.util.Optional.of(Vendor.builder().id("V2").name("B").build()));
        when(ledgerRepository.findByVendorIdOrderByEntryDateAscCreatedAtAsc("V1")).thenReturn(List.of(
                entry(SupplierLedgerEntryType.BILL_BOOKED, "1000", "0", D), entry(SupplierLedgerEntryType.PAYMENT_MADE, "0", "250", D)));
        when(ledgerRepository.findByVendorIdOrderByEntryDateAscCreatedAtAsc("V2")).thenReturn(List.of(
                entry(SupplierLedgerEntryType.BILL_BOOKED, "500", "0", D)));

        assertThat(service.totalPaidAllVendors()).isEqualByComparingTo("250");
        assertThat(service.totalPayableAllVendors()).isEqualByComparingTo("1250");
        assertThat(service.summaryForAllVendors()).extracting(VendorLedgerSummaryResponse::getVendorName).containsExactly("A", "B");
    }

    private static SupplierInvoice bill(String vendorId, SupplierInvoiceStatus status, String due, LocalDate dueDate, LocalDate invoiceDate) {
        return SupplierInvoice.builder().id("SI-" + due).vendorId(vendorId).vendorName(vendorId == null ? null : "Name " + vendorId)
                .status(status).balanceDueInr(new BigDecimal(due)).dueDate(dueDate).invoiceDate(invoiceDate).build();
    }

    @Test
    void ageingBucketsEachOpenBillByDaysPastDue() {
        LocalDate today = LocalDate.now();
        when(supplierInvoiceRepository.findAll()).thenReturn(List.of(
                bill("V1", SupplierInvoiceStatus.APPROVED, "1", today.plusDays(5), null),
                bill("V1", SupplierInvoiceStatus.PARTIALLY_PAID, "2", today.minusDays(30), null),
                bill("V1", SupplierInvoiceStatus.APPROVED, "4", null, today.minusDays(31)),
                bill("V1", SupplierInvoiceStatus.APPROVED, "8", today.minusDays(90), null),
                bill("V1", SupplierInvoiceStatus.APPROVED, "16", today.minusDays(91), null),
                bill("V1", SupplierInvoiceStatus.APPROVED, "32", null, null),
                bill("V1", SupplierInvoiceStatus.APPROVED, "0", today.minusDays(91), null),
                bill("V1", SupplierInvoiceStatus.DRAFT, "64", today.minusDays(91), null),
                bill(null, SupplierInvoiceStatus.APPROVED, "128", null, null),
                bill("V2", SupplierInvoiceStatus.APPROVED, "100", today.minusDays(61), null)));
        when(ledgerRepository.findAll()).thenReturn(List.of(
                SupplierLedgerEntry.builder().vendorId("V1").creditAmountInr(new BigDecimal("63")).debitAmountInr(BigDecimal.ZERO).build(),
                SupplierLedgerEntry.builder().vendorId(null).creditAmountInr(new BigDecimal("999")).debitAmountInr(BigDecimal.ZERO).build(),
                SupplierLedgerEntry.builder().vendorId("V3").creditAmountInr(BigDecimal.ZERO).debitAmountInr(new BigDecimal("700")).build(),
                SupplierLedgerEntry.builder().vendorId("V4").creditAmountInr(new BigDecimal("10")).debitAmountInr(new BigDecimal("10")).build()));
        when(vendorRepository.findAll()).thenReturn(List.of(Vendor.builder().id("V3").name("Deposit Co").build()));

        List<ApAgeingRowResponse> rows = service.outstanding();

        assertThat(rows).extracting(ApAgeingRowResponse::getVendorId).containsExactly("V1", "V2", "V3");
        ApAgeingRowResponse v1 = rows.get(0);
        assertThat(v1.getVendorName()).isEqualTo("Name V1");
        assertThat(v1.getCurrent()).isEqualByComparingTo("33");
        assertThat(v1.getDays1To30()).isEqualByComparingTo("2");
        assertThat(v1.getDays31To60()).isEqualByComparingTo("4");
        assertThat(v1.getDays61To90()).isEqualByComparingTo("8");
        assertThat(v1.getDays90Plus()).isEqualByComparingTo("16");
        assertThat(v1.getTotalPayableInr()).isEqualByComparingTo("63");
        assertThat(v1.getCreditsInr()).isEqualByComparingTo("0");
        ApAgeingRowResponse v2 = rows.get(1);
        assertThat(v2.getDays61To90()).isEqualByComparingTo("100");
        assertThat(v2.getNetBalanceInr()).isEqualByComparingTo("100");
        ApAgeingRowResponse v3 = rows.get(2);
        assertThat(v3.getVendorName()).isEqualTo("Deposit Co");
        assertThat(v3.getTotalPayableInr()).isEqualByComparingTo("0");
        assertThat(v3.getCreditsInr()).isEqualByComparingTo("-700");
    }

    @Test
    void theAgeingSpreadsheetShowsAnUnnamedVendorAsBlank() throws Exception {
        when(supplierInvoiceRepository.findAll()).thenReturn(List.of(
                bill("V1", SupplierInvoiceStatus.APPROVED, "75", LocalDate.now().plusDays(1), null)));
        when(ledgerRepository.findAll()).thenReturn(List.of(
                SupplierLedgerEntry.builder().vendorId("V7").creditAmountInr(BigDecimal.ZERO).debitAmountInr(new BigDecimal("50")).build()));
        when(vendorRepository.findAll()).thenReturn(List.of(Vendor.builder().id("V1").name("Air Co").build()));

        byte[] xlsx = service.exportOutstandingXlsx();

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(8).getStringCellValue()).isEqualTo("Net Balance (INR)");
            assertThat(cell(sheet.getRow(1), 0)).isEqualTo("Name V1");
            assertThat(cell(sheet.getRow(1), 1)).isEqualTo("75");
            assertThat(cell(sheet.getRow(2), 0)).isEmpty();
            assertThat(cell(sheet.getRow(2), 8)).isEqualTo("-50");
            assertThat(sheet.getLastRowNum()).isEqualTo(2);
        }
    }

    @Test
    void aPositiveOpeningBalanceIsOwedToTheVendorAndPostedAgainstEquity() {
        vendor();
        SupplierOpeningBalanceRequest r = new SupplierOpeningBalanceRequest();
        r.setAmount(new BigDecimal("1500"));
        r.setAsOfDate(D);
        r.setNote(" ");

        service.openingBalance("V1", r);

        ArgumentCaptor<SupplierLedgerEntry> entry = ArgumentCaptor.forClass(SupplierLedgerEntry.class);
        verify(ledgerRepository).save(entry.capture());
        assertThat(entry.getValue().getEntryType()).isEqualTo(SupplierLedgerEntryType.OPENING_BALANCE);
        assertThat(entry.getValue().getSourceId()).isEqualTo("V1");
        assertThat(entry.getValue().getDocumentNumber()).isEqualTo("OB-2026-09-01");
        assertThat(entry.getValue().getNarration()).isEqualTo("Opening balance");
        assertThat(entry.getValue().getCreditAmountInr()).isEqualByComparingTo("1500");
        assertThat(entry.getValue().getDebitAmountInr()).isEqualByComparingTo("0");
        assertThat(entry.getValue().getCurrencyCode()).isEqualTo("INR");
        ArgumentCaptor<JournalPosting> journal = ArgumentCaptor.forClass(JournalPosting.class);
        verify(journalService).post(journal.capture());
        assertThat(journal.getValue().sourceType()).isEqualTo(JournalSourceType.OPENING_BALANCE);
        assertThat(journal.getValue().purpose()).isEqualTo(JournalPurpose.OPENING_BALANCE_POSTED);
        assertThat(journal.getValue().narration()).isEqualTo("Opening balance - Air Co");
        assertThat(journal.getValue().lines()).containsExactly(
                JournalLinePosting.debit(SystemAccount.OPENING_BALANCE_EQUITY.code(), new BigDecimal("1500"), "Opening balance - Air Co"),
                JournalLinePosting.creditParty(SystemAccount.ACCOUNTS_PAYABLE.code(), "VENDOR", "V1", new BigDecimal("1500"), "Opening balance - Air Co"));
    }

    @Test
    void aNegativeOpeningBalanceIsAnAdvanceWithTheVendor() {
        vendor();
        SupplierOpeningBalanceRequest r = new SupplierOpeningBalanceRequest();
        r.setAmount(new BigDecimal("-200"));
        r.setAsOfDate(D);
        r.setNote("Deposit from 2025");

        service.openingBalance("V1", r);

        ArgumentCaptor<SupplierLedgerEntry> entry = ArgumentCaptor.forClass(SupplierLedgerEntry.class);
        verify(ledgerRepository).save(entry.capture());
        assertThat(entry.getValue().getNarration()).isEqualTo("Deposit from 2025");
        assertThat(entry.getValue().getDebitAmountInr()).isEqualByComparingTo("200");
        assertThat(entry.getValue().getCreditAmountInr()).isEqualByComparingTo("0");
        ArgumentCaptor<JournalPosting> journal = ArgumentCaptor.forClass(JournalPosting.class);
        verify(journalService).post(journal.capture());
        assertThat(journal.getValue().lines()).containsExactly(
                JournalLinePosting.debitParty(SystemAccount.ACCOUNTS_PAYABLE.code(), "VENDOR", "V1", new BigDecimal("200"), "Opening balance - Air Co"),
                JournalLinePosting.credit(SystemAccount.OPENING_BALANCE_EQUITY.code(), new BigDecimal("200"), "Opening balance - Air Co"));
    }

    @Test
    void anOpeningBalanceWithNoNoteIsLabelledOpeningBalance() {
        vendor();
        SupplierOpeningBalanceRequest r = new SupplierOpeningBalanceRequest();
        r.setAmount(BigDecimal.ZERO);
        r.setAsOfDate(D);

        service.openingBalance("V1", r);

        ArgumentCaptor<SupplierLedgerEntry> entry = ArgumentCaptor.forClass(SupplierLedgerEntry.class);
        verify(ledgerRepository).save(entry.capture());
        assertThat(entry.getValue().getNarration()).isEqualTo("Opening balance");
    }
}
