package com.voyra.crm.service;

import com.voyra.crm.dto.BalanceSheetResponse;
import com.voyra.crm.dto.BalanceSheetRowResponse;
import com.voyra.crm.dto.TrialBalanceResponse;
import com.voyra.crm.entity.JournalLine;
import com.voyra.crm.entity.LedgerAccount;
import com.voyra.crm.enums.LedgerAccountType;
import com.voyra.crm.repository.JournalLineRepository;
import com.voyra.crm.repository.LedgerAccountRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FinancialStatementsServiceCoverageTest {

    @Mock private JournalLineRepository journalLineRepository;
    @Mock private LedgerAccountRepository ledgerAccountRepository;
    @InjectMocks private FinancialStatementsService service;

    private static final LocalDate FROM = LocalDate.of(2026, 4, 1);
    private static final LocalDate TO = LocalDate.of(2027, 3, 31);

    private static LedgerAccount account(String code, String name, LedgerAccountType type) {
        return LedgerAccount.builder().id(code).code(code).name(name).accountType(type).parentCode(type == null ? null : "P" + code).build();
    }

    private static JournalLine line(String code, String debit, String credit) {
        return JournalLine.builder().accountCode(code).debitAmountInr(new BigDecimal(debit)).creditAmountInr(new BigDecimal(credit)).build();
    }

    private static List<List<String>> rows(byte[] xlsx) throws Exception {
        List<List<String>> out = new ArrayList<>();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = wb.getSheetAt(0);
            for (Row r : sheet) {
                List<String> cells = new ArrayList<>();
                for (int i = 0; i < r.getLastCellNum(); i++) {
                    var c = r.getCell(i);
                    cells.add(c == null ? "" : c.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC
                            ? new BigDecimal(String.valueOf(c.getNumericCellValue())).stripTrailingZeros().toPlainString()
                            : c.getStringCellValue());
                }
                out.add(cells);
            }
        }
        return out;
    }

    @Test
    void anAccountMissingFromTheChartIsShownByItsCodeAndDuplicatesKeepTheFirst() {
        when(journalLineRepository.findLedgerLinesBetween(FROM, TO)).thenReturn(List.of(line("1100", "100", "0"), line("9999", "0", "100")));
        when(ledgerAccountRepository.findAll()).thenReturn(List.of(account("1100", "Cash", LedgerAccountType.ASSET),
                account("1100", "Cash duplicate", LedgerAccountType.ASSET)));

        TrialBalanceResponse tb = service.trialBalance(FROM, TO);

        assertThat(tb.getRows()).extracting(r -> r.getAccountName()).containsExactly("Cash", "9999");
        assertThat(tb.getRows().get(1).getAccountType()).isNull();
        assertThat(tb.getRows().get(1).getParentCode()).isNull();
        assertThat(tb.getRows().get(0).getParentCode()).isEqualTo("P1100");
        assertThat(tb.getTotalDebitInr()).isEqualByComparingTo("100.00");
        assertThat(tb.getTotalCreditInr()).isEqualByComparingTo("100.00");
    }

    @Test
    void theTrialBalanceSpreadsheetLeavesAnUnknownTypeBlank() throws Exception {
        when(journalLineRepository.findLedgerLinesBetween(FROM, TO)).thenReturn(List.of(line("1100", "100", "0"), line("9999", "0", "100")));
        when(ledgerAccountRepository.findAll()).thenReturn(List.of(account("1100", "Cash", LedgerAccountType.ASSET)));

        List<List<String>> rows = rows(service.exportTrialBalanceXlsx(FROM, TO));

        assertThat(rows.get(0)).containsExactly("Account Code", "Account Name", "Type", "Debit (INR)", "Credit (INR)");
        assertThat(rows.get(1)).containsExactly("1100", "Cash", "ASSET", "100.00", "0.00");
        assertThat(rows.get(2)).containsExactly("9999", "9999", "", "0.00", "100.00");
    }

    @Test
    void theProfitAndLossSpreadsheetEndsWithTheNetProfit() throws Exception {
        when(journalLineRepository.findLedgerLinesBetween(FROM, TO)).thenReturn(List.of(
                line("4000", "0", "1000"), line("5000", "600", "0"), line("1100", "400", "0"), line("9999", "1", "0")));
        when(ledgerAccountRepository.findAll()).thenReturn(List.of(account("4000", "Sales", LedgerAccountType.INCOME),
                account("5000", "Purchases", LedgerAccountType.EXPENSE), account("1100", "Cash", LedgerAccountType.ASSET)));

        List<List<String>> rows = rows(service.exportProfitAndLossXlsx(FROM, TO));

        assertThat(rows).extracting(r -> String.join("|", r)).containsExactly(
                "Account Code|Account Name|Net Amount (INR)", "4000|Sales|1000.00", "5000|Purchases|600.00", "|Net Profit|400.00");
    }

    @Test
    void retainedEarningsAlreadyOnTheLedgerTakeTheFoldedProfit() throws Exception {
        LocalDate asOf = LocalDate.of(2027, 3, 31);
        when(journalLineRepository.findLedgerLinesAsOf(asOf)).thenReturn(List.of(
                line("1100", "1500", "0"), line("2200", "0", "200"), line("3200", "0", "300"), line("3100", "0", "600"),
                line("4000", "0", "400"), line("9999", "0", "0")));
        when(ledgerAccountRepository.findAll()).thenReturn(List.of(account("1100", "Cash", LedgerAccountType.ASSET),
                account("2200", "Payables", LedgerAccountType.LIABILITY), account("3100", "Capital", LedgerAccountType.EQUITY),
                account("3200", "Retained Earnings", LedgerAccountType.EQUITY), account("4000", "Sales", LedgerAccountType.INCOME)));

        BalanceSheetResponse bs = service.balanceSheet(asOf);

        assertThat(bs.getEquityRows()).extracting(BalanceSheetRowResponse::getAccountCode, r -> r.getBalanceInr().toPlainString())
                .containsExactly(org.assertj.core.groups.Tuple.tuple("3100", "600.00"), org.assertj.core.groups.Tuple.tuple("3200", "700.00"));
        assertThat(bs.getTotalAssetsInr()).isEqualByComparingTo("1500.00");
        assertThat(bs.getTotalLiabilitiesInr()).isEqualByComparingTo("200.00");
        assertThat(bs.getTotalEquityInr()).isEqualByComparingTo("1300.00");
        assertThat(rows(service.exportBalanceSheetXlsx(asOf))).extracting(r -> String.join("|", r)).containsExactly(
                "Account Code|Account Name|Balance (INR)", "1100|Cash|1500.00", "2200|Payables|200.00", "3100|Capital|600.00",
                "3200|Retained Earnings|700.00");
    }

    @Test
    void anUnmovedRetainedEarningsAccountIsAddedUnderItsChartName() {
        LocalDate asOf = LocalDate.of(2027, 3, 31);
        when(journalLineRepository.findLedgerLinesAsOf(asOf)).thenReturn(List.of(line("1100", "50", "0"), line("4000", "0", "50")));
        when(ledgerAccountRepository.findAll()).thenReturn(List.of(account("1100", "Cash", LedgerAccountType.ASSET),
                account("3200", "Profit Reserve", LedgerAccountType.EQUITY), account("4000", "Sales", LedgerAccountType.INCOME)));

        BalanceSheetResponse bs = service.balanceSheet(asOf);

        assertThat(bs.getEquityRows()).singleElement().satisfies(r -> {
            assertThat(r.getAccountName()).isEqualTo("Profit Reserve");
            assertThat(r.getBalanceInr()).isEqualByComparingTo("50.00");
        });
    }

    @Test
    void aChartWithoutRetainedEarningsStillShowsTheFoldedProfit() {
        LocalDate asOf = LocalDate.of(2027, 3, 31);
        when(journalLineRepository.findLedgerLinesAsOf(asOf)).thenReturn(List.of(line("5000", "30", "0"), line("1100", "0", "30")));
        when(ledgerAccountRepository.findAll()).thenReturn(List.of(account("1100", "Cash", LedgerAccountType.ASSET),
                account("5000", "Purchases", LedgerAccountType.EXPENSE)));

        BalanceSheetResponse bs = service.balanceSheet(asOf);

        assertThat(bs.getEquityRows()).singleElement().satisfies(r -> {
            assertThat(r.getAccountCode()).isEqualTo("3200");
            assertThat(r.getAccountName()).isEqualTo("Retained Earnings");
            assertThat(r.getBalanceInr()).isEqualByComparingTo("-30.00");
        });
        assertThat(bs.getTotalAssetsInr()).isEqualByComparingTo("-30.00");
    }
}
