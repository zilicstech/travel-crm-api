package com.voyra.crm.service;

import com.voyra.crm.dto.BalanceSheetResponse;
import com.voyra.crm.dto.ProfitAndLossResponse;
import com.voyra.crm.dto.TrialBalanceResponse;
import com.voyra.crm.entity.JournalLine;
import com.voyra.crm.entity.LedgerAccount;
import com.voyra.crm.enums.LedgerAccountType;
import com.voyra.crm.repository.JournalLineRepository;
import com.voyra.crm.repository.LedgerAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * ACCOUNTING_EXPANSION_ARCHITECTURE.md §1.9. {@code JournalLineRepository.findLedgerLinesBetween}/
 * {@code findLedgerLinesAsOf} already filter to POSTED-only entries in their JPQL
 * ({@code WHERE je.status = JournalStatus.POSTED}) - that filter is proven by inspection of the
 * query, not re-tested here, matching this codebase's existing convention of no
 * {@code @DataJpaTest} harness. What IS tested here is everything downstream of that filter: the
 * grouping, the debit/credit arithmetic, and the Balance Sheet's retained-earnings fold.
 */
@ExtendWith(MockitoExtension.class)
class FinancialStatementsServiceTest {

    @Mock
    private JournalLineRepository journalLineRepository;
    @Mock
    private LedgerAccountRepository ledgerAccountRepository;

    @InjectMocks
    private FinancialStatementsService financialStatementsService;

    private static LedgerAccount account(String code, String name, LedgerAccountType type) {
        return LedgerAccount.builder().id(code).code(code).name(name).accountType(type)
                .isSystem(true).isControl(false).isActive(true).build();
    }

    private static JournalLine debit(String accountCode, String amount) {
        return JournalLine.builder().id("L-" + accountCode + "-D-" + amount)
                .accountCode(accountCode).debitAmountInr(new BigDecimal(amount)).creditAmountInr(BigDecimal.ZERO).build();
    }

    private static JournalLine credit(String accountCode, String amount) {
        return JournalLine.builder().id("L-" + accountCode + "-C-" + amount)
                .accountCode(accountCode).debitAmountInr(BigDecimal.ZERO).creditAmountInr(new BigDecimal(amount)).build();
    }

    @Test
    void trialBalanceAlwaysBalancesAndGroupsByAccount() {
        // One invoice raised (immediate recognition): debit AR 1180, credit Sales 1000 + Output GST 180.
        // One receipt against it: debit Bank 1180, credit AR 1180.
        List<JournalLine> lines = List.of(
                debit("1200", "1180.00"), credit("4010", "1000.00"), credit("2310", "180.00"),
                debit("1110", "1180.00"), credit("1200", "1180.00"));
        when(journalLineRepository.findLedgerLinesBetween(any(), any())).thenReturn(lines);
        when(ledgerAccountRepository.findAll()).thenReturn(List.of(
                account("1200", "Accounts Receivable", LedgerAccountType.ASSET),
                account("1110", "Bank Accounts", LedgerAccountType.ASSET),
                account("4010", "Sales A/c (Package)", LedgerAccountType.INCOME),
                account("2310", "Output GST Payable", LedgerAccountType.LIABILITY)));

        TrialBalanceResponse report = financialStatementsService.trialBalance(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 9, 30));

        assertThat(report.getRows()).hasSize(4);
        assertThat(report.getTotalDebitInr()).isEqualByComparingTo(report.getTotalCreditInr());
        assertThat(report.getTotalDebitInr()).isEqualByComparingTo("2360.00");

        assertThat(report.getRows().stream().filter(r -> r.getAccountCode().equals("1200")).findFirst().orElseThrow())
                .satisfies(row -> {
                    assertThat(row.getDebitInr()).isEqualByComparingTo("1180.00");
                    assertThat(row.getCreditInr()).isEqualByComparingTo("1180.00");
                });
    }

    @Test
    void profitAndLossNetsIncomeMinusExpense() {
        List<JournalLine> lines = List.of(
                credit("4010", "1000.00"), credit("4020", "500.00"),
                debit("5010", "300.00"), debit("5620", "50.00"));
        when(journalLineRepository.findLedgerLinesBetween(any(), any())).thenReturn(lines);
        when(ledgerAccountRepository.findAll()).thenReturn(List.of(
                account("4010", "Sales A/c (Package)", LedgerAccountType.INCOME),
                account("4020", "Sales A/c (Hotel)", LedgerAccountType.INCOME),
                account("5010", "Purchase A/c (Package)", LedgerAccountType.EXPENSE),
                account("5620", "Bank Charges", LedgerAccountType.EXPENSE)));

        ProfitAndLossResponse report = financialStatementsService.profitAndLoss(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 9, 30));

        assertThat(report.getTotalIncomeInr()).isEqualByComparingTo("1500.00");
        assertThat(report.getTotalExpenseInr()).isEqualByComparingTo("350.00");
        assertThat(report.getNetProfitInr()).isEqualByComparingTo("1150.00"); // hand-computed: 1500 - 350
        assertThat(report.getIncomeRows()).hasSize(2);
        assertThat(report.getExpenseRows()).hasSize(2);
    }

    @Test
    void balanceSheetBalancesAcrossTwoPeriodsWithRetainedEarningsFolded() {
        // Period 1 (through 30 Jun): invoice raised and fully collected - net profit 1150 sits unposted anywhere but the P&L.
        // Period 2 (through 30 Sep, cumulative): a second sale on credit, not yet collected.
        // asOf = 2026-09-30, cumulative since inception.
        List<JournalLine> cumulativeLines = List.of(
                // Period 1: raise + collect
                debit("1200", "1180.00"), credit("4010", "1000.00"), credit("2310", "180.00"),
                debit("1110", "1180.00"), credit("1200", "1180.00"),
                // Period 2: raise, still outstanding
                debit("1200", "590.00"), credit("4010", "500.00"), credit("2310", "90.00"));
        when(journalLineRepository.findLedgerLinesAsOf(LocalDate.of(2026, 9, 30))).thenReturn(cumulativeLines);
        when(ledgerAccountRepository.findAll()).thenReturn(List.of(
                account("1200", "Accounts Receivable", LedgerAccountType.ASSET),
                account("1110", "Bank Accounts", LedgerAccountType.ASSET),
                account("4010", "Sales A/c (Package)", LedgerAccountType.INCOME),
                account("2310", "Output GST Payable", LedgerAccountType.LIABILITY),
                account("3200", "Retained Earnings", LedgerAccountType.EQUITY)));

        BalanceSheetResponse sheet = financialStatementsService.balanceSheet(LocalDate.of(2026, 9, 30));

        // Assets: AR = 1180 - 1180 + 590 = 590; Bank = 1180. Total assets = 1770.
        assertThat(sheet.getTotalAssetsInr()).isEqualByComparingTo("1770.00");
        // Liabilities: Output GST = 180 + 90 = 270.
        assertThat(sheet.getTotalLiabilitiesInr()).isEqualByComparingTo("270.00");
        // Equity: 3200 had no actual ledger balance (nothing posts to it directly) - the whole
        // 1500.00 cumulative net profit (1000 + 500 income, 0 expense) is folded in at report time.
        assertThat(sheet.getTotalEquityInr()).isEqualByComparingTo("1500.00");
        assertThat(sheet.getEquityRows()).anySatisfy(row -> {
            assertThat(row.getAccountCode()).isEqualTo("3200");
            assertThat(row.getBalanceInr()).isEqualByComparingTo("1500.00");
        });

        // The whole point: Assets = Liabilities + Equity.
        assertThat(sheet.getTotalAssetsInr())
                .isEqualByComparingTo(sheet.getTotalLiabilitiesInr().add(sheet.getTotalEquityInr()));
    }

    @Test
    void anAccountWithNoMovementNeverAppearsInAnyStatement() {
        when(journalLineRepository.findLedgerLinesBetween(any(), any())).thenReturn(List.of(debit("1110", "100.00"), credit("4010", "100.00")));
        when(ledgerAccountRepository.findAll()).thenReturn(List.of(
                account("1110", "Bank Accounts", LedgerAccountType.ASSET),
                account("4010", "Sales A/c (Package)", LedgerAccountType.INCOME),
                account("1300", "Supplier Advances", LedgerAccountType.ASSET))); // never moved

        TrialBalanceResponse report = financialStatementsService.trialBalance(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 9, 30));

        assertThat(report.getRows()).extracting("accountCode").doesNotContain("1300");
    }
}
