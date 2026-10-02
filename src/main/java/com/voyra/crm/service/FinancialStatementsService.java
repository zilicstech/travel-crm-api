package com.voyra.crm.service;

import com.voyra.crm.dto.BalanceSheetResponse;
import com.voyra.crm.dto.BalanceSheetRowResponse;
import com.voyra.crm.dto.ProfitAndLossResponse;
import com.voyra.crm.dto.ProfitAndLossRowResponse;
import com.voyra.crm.dto.TrialBalanceResponse;
import com.voyra.crm.dto.TrialBalanceRowResponse;
import com.voyra.crm.entity.JournalLine;
import com.voyra.crm.entity.LedgerAccount;
import com.voyra.crm.enums.LedgerAccountType;
import com.voyra.crm.enums.SystemAccount;
import com.voyra.crm.repository.JournalLineRepository;
import com.voyra.crm.repository.LedgerAccountRepository;
import com.voyra.crm.util.ReportTable;
import com.voyra.crm.util.XlsxWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Trial Balance, P&amp;L and Balance Sheet - ACCOUNTING_EXPANSION_ARCHITECTURE.md §1.9. Reads
 * only {@code journal_line} for {@code POSTED} entries (a {@code REVERSED} entry's lines are
 * excluded by {@link JournalLineRepository}'s query, not filtered here) - the GST/TCS registers on
 * {@code AccountsDashboardController} stay untouched and keep reading the source documents
 * directly, per Rule 1.9's explicit instruction not to re-derive them from the GL.
 */
@Service
@RequiredArgsConstructor
public class FinancialStatementsService {

    private static final BigDecimal TWO_DP = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final JournalLineRepository journalLineRepository;
    private final LedgerAccountRepository ledgerAccountRepository;

    @Transactional(readOnly = true)
    public TrialBalanceResponse trialBalance(LocalDate from, LocalDate to) {
        List<JournalLine> lines = journalLineRepository.findPostedLinesBetween(from, to);
        Map<String, LedgerAccount> accountsByCode = accountsByCode();

        Map<String, BigDecimal> debitByAccount = sumByAccount(lines, JournalLine::getDebitAmountInr);
        Map<String, BigDecimal> creditByAccount = sumByAccount(lines, JournalLine::getCreditAmountInr);

        List<TrialBalanceRowResponse> rows = movedAccountCodes(debitByAccount, creditByAccount).stream()
                .map(code -> {
                    LedgerAccount account = accountsByCode.get(code);
                    return TrialBalanceRowResponse.builder()
                            .accountCode(code)
                            .accountName(account != null ? account.getName() : code)
                            .accountType(account != null ? account.getAccountType() : null)
                            .parentCode(account != null ? account.getParentCode() : null)
                            .debitInr(scale(debitByAccount.getOrDefault(code, BigDecimal.ZERO)))
                            .creditInr(scale(creditByAccount.getOrDefault(code, BigDecimal.ZERO)))
                            .build();
                })
                .sorted(Comparator.comparing(TrialBalanceRowResponse::getAccountCode))
                .toList();

        BigDecimal totalDebit = rows.stream().map(TrialBalanceRowResponse::getDebitInr).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = rows.stream().map(TrialBalanceRowResponse::getCreditInr).reduce(BigDecimal.ZERO, BigDecimal::add);

        return TrialBalanceResponse.builder()
                .from(from).to(to).rows(rows)
                .totalDebitInr(scale(totalDebit)).totalCreditInr(scale(totalCredit))
                .build();
    }

    @Transactional(readOnly = true)
    public ProfitAndLossResponse profitAndLoss(LocalDate from, LocalDate to) {
        List<JournalLine> lines = journalLineRepository.findPostedLinesBetween(from, to);
        return profitAndLossFromLines(lines, from, to);
    }

    @Transactional(readOnly = true)
    public BalanceSheetResponse balanceSheet(LocalDate asOf) {
        List<JournalLine> lines = journalLineRepository.findPostedLinesAsOf(asOf);
        Map<String, LedgerAccount> accountsByCode = accountsByCode();

        Map<String, BigDecimal> debitByAccount = sumByAccount(lines, JournalLine::getDebitAmountInr);
        Map<String, BigDecimal> creditByAccount = sumByAccount(lines, JournalLine::getCreditAmountInr);

        List<BalanceSheetRowResponse> assetRows = rowsForTypes(accountsByCode, debitByAccount, creditByAccount, LedgerAccountType.ASSET);
        List<BalanceSheetRowResponse> liabilityRows = rowsForTypes(accountsByCode, debitByAccount, creditByAccount, LedgerAccountType.LIABILITY);
        List<BalanceSheetRowResponse> equityRows = new ArrayList<>(
                rowsForTypes(accountsByCode, debitByAccount, creditByAccount, LedgerAccountType.EQUITY));

        // Rule 1.9: fold the period's P&L result (account inception through asOf) into 3200
        // Retained Earnings at report time. Nothing is ever posted to 3200 for this - it is a
        // pure report-time computation, so the fold is applied to the row the caller sees, never
        // to the ledger.
        ProfitAndLossResponse cumulativePl = profitAndLossFromLines(lines, LocalDate.of(1970, 1, 1), asOf);
        String retainedEarningsCode = SystemAccount.RETAINED_EARNINGS.code();
        boolean foldedIntoExisting = false;
        for (int i = 0; i < equityRows.size(); i++) {
            BalanceSheetRowResponse row = equityRows.get(i);
            if (row.getAccountCode().equals(retainedEarningsCode)) {
                equityRows.set(i, BalanceSheetRowResponse.builder()
                        .accountCode(row.getAccountCode())
                        .accountName(row.getAccountName())
                        .balanceInr(scale(row.getBalanceInr().add(cumulativePl.getNetProfitInr())))
                        .build());
                foldedIntoExisting = true;
                break;
            }
        }
        if (!foldedIntoExisting) {
            LedgerAccount retainedEarnings = accountsByCode.get(retainedEarningsCode);
            equityRows.add(BalanceSheetRowResponse.builder()
                    .accountCode(retainedEarningsCode)
                    .accountName(retainedEarnings != null ? retainedEarnings.getName() : "Retained Earnings")
                    .balanceInr(scale(cumulativePl.getNetProfitInr()))
                    .build());
        }
        equityRows.sort(Comparator.comparing(BalanceSheetRowResponse::getAccountCode));

        BigDecimal totalAssets = sumBalances(assetRows);
        BigDecimal totalLiabilities = sumBalances(liabilityRows);
        BigDecimal totalEquity = sumBalances(equityRows);

        return BalanceSheetResponse.builder()
                .asOf(asOf)
                .assetRows(assetRows).liabilityRows(liabilityRows).equityRows(equityRows)
                .totalAssetsInr(scale(totalAssets))
                .totalLiabilitiesInr(scale(totalLiabilities))
                .totalEquityInr(scale(totalEquity))
                .build();
    }

    @Transactional(readOnly = true)
    public byte[] exportTrialBalanceXlsx(LocalDate from, LocalDate to) {
        TrialBalanceResponse report = trialBalance(from, to);
        List<String> header = List.of("Account Code", "Account Name", "Type", "Debit (INR)", "Credit (INR)");
        List<List<String>> data = report.getRows().stream()
                .map(r -> List.of(r.getAccountCode(), r.getAccountName(),
                        r.getAccountType() != null ? r.getAccountType().toString() : "",
                        r.getDebitInr().toString(), r.getCreditInr().toString()))
                .toList();
        return XlsxWriter.write("Trial Balance", new ReportTable(header, data));
    }

    @Transactional(readOnly = true)
    public byte[] exportProfitAndLossXlsx(LocalDate from, LocalDate to) {
        ProfitAndLossResponse report = profitAndLoss(from, to);
        List<String> header = List.of("Account Code", "Account Name", "Net Amount (INR)");
        List<List<String>> data = new ArrayList<>();
        report.getIncomeRows().forEach(r -> data.add(List.of(r.getAccountCode(), r.getAccountName(), r.getNetAmountInr().toString())));
        report.getExpenseRows().forEach(r -> data.add(List.of(r.getAccountCode(), r.getAccountName(), r.getNetAmountInr().toString())));
        data.add(List.of("", "Net Profit", report.getNetProfitInr().toString()));
        return XlsxWriter.write("Profit and Loss", new ReportTable(header, data));
    }

    @Transactional(readOnly = true)
    public byte[] exportBalanceSheetXlsx(LocalDate asOf) {
        BalanceSheetResponse report = balanceSheet(asOf);
        List<String> header = List.of("Account Code", "Account Name", "Balance (INR)");
        List<List<String>> data = new ArrayList<>();
        report.getAssetRows().forEach(r -> data.add(List.of(r.getAccountCode(), r.getAccountName(), r.getBalanceInr().toString())));
        report.getLiabilityRows().forEach(r -> data.add(List.of(r.getAccountCode(), r.getAccountName(), r.getBalanceInr().toString())));
        report.getEquityRows().forEach(r -> data.add(List.of(r.getAccountCode(), r.getAccountName(), r.getBalanceInr().toString())));
        return XlsxWriter.write("Balance Sheet", new ReportTable(header, data));
    }

    private ProfitAndLossResponse profitAndLossFromLines(List<JournalLine> lines, LocalDate from, LocalDate to) {
        Map<String, LedgerAccount> accountsByCode = accountsByCode();
        Map<String, BigDecimal> debitByAccount = sumByAccount(lines, JournalLine::getDebitAmountInr);
        Map<String, BigDecimal> creditByAccount = sumByAccount(lines, JournalLine::getCreditAmountInr);

        List<ProfitAndLossRowResponse> incomeRows = netRowsForType(
                accountsByCode, debitByAccount, creditByAccount, LedgerAccountType.INCOME);
        List<ProfitAndLossRowResponse> expenseRows = netRowsForType(
                accountsByCode, debitByAccount, creditByAccount, LedgerAccountType.EXPENSE);

        BigDecimal totalIncome = incomeRows.stream().map(ProfitAndLossRowResponse::getNetAmountInr).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalExpense = expenseRows.stream().map(ProfitAndLossRowResponse::getNetAmountInr).reduce(BigDecimal.ZERO, BigDecimal::add);

        return ProfitAndLossResponse.builder()
                .from(from).to(to)
                .incomeRows(incomeRows).expenseRows(expenseRows)
                .totalIncomeInr(scale(totalIncome)).totalExpenseInr(scale(totalExpense))
                .netProfitInr(scale(totalIncome.subtract(totalExpense)))
                .build();
    }

    private List<ProfitAndLossRowResponse> netRowsForType(Map<String, LedgerAccount> accountsByCode,
            Map<String, BigDecimal> debitByAccount, Map<String, BigDecimal> creditByAccount, LedgerAccountType type) {
        return movedAccountCodes(debitByAccount, creditByAccount).stream()
                .filter(code -> accountsByCode.containsKey(code) && accountsByCode.get(code).getAccountType() == type)
                .map(code -> {
                    LedgerAccount account = accountsByCode.get(code);
                    BigDecimal debit = debitByAccount.getOrDefault(code, BigDecimal.ZERO);
                    BigDecimal credit = creditByAccount.getOrDefault(code, BigDecimal.ZERO);
                    BigDecimal net = type == LedgerAccountType.INCOME ? credit.subtract(debit) : debit.subtract(credit);
                    return ProfitAndLossRowResponse.builder()
                            .accountCode(code).accountName(account.getName()).netAmountInr(scale(net))
                            .build();
                })
                .sorted(Comparator.comparing(ProfitAndLossRowResponse::getAccountCode))
                .toList();
    }

    private List<BalanceSheetRowResponse> rowsForTypes(Map<String, LedgerAccount> accountsByCode,
            Map<String, BigDecimal> debitByAccount, Map<String, BigDecimal> creditByAccount, LedgerAccountType type) {
        boolean normalDebitBalance = type == LedgerAccountType.ASSET;
        return movedAccountCodes(debitByAccount, creditByAccount).stream()
                .filter(code -> accountsByCode.containsKey(code) && accountsByCode.get(code).getAccountType() == type)
                .map(code -> {
                    LedgerAccount account = accountsByCode.get(code);
                    BigDecimal debit = debitByAccount.getOrDefault(code, BigDecimal.ZERO);
                    BigDecimal credit = creditByAccount.getOrDefault(code, BigDecimal.ZERO);
                    BigDecimal balance = normalDebitBalance ? debit.subtract(credit) : credit.subtract(debit);
                    return BalanceSheetRowResponse.builder()
                            .accountCode(code).accountName(account.getName()).balanceInr(scale(balance))
                            .build();
                })
                .sorted(Comparator.comparing(BalanceSheetRowResponse::getAccountCode))
                .toList();
    }

    private static BigDecimal sumBalances(List<BalanceSheetRowResponse> rows) {
        return rows.stream().map(BalanceSheetRowResponse::getBalanceInr).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static List<String> movedAccountCodes(Map<String, BigDecimal> debitByAccount, Map<String, BigDecimal> creditByAccount) {
        return java.util.stream.Stream.concat(debitByAccount.keySet().stream(), creditByAccount.keySet().stream())
                .distinct().toList();
    }

    private static Map<String, BigDecimal> sumByAccount(List<JournalLine> lines, Function<JournalLine, BigDecimal> amount) {
        return lines.stream()
                .collect(Collectors.groupingBy(JournalLine::getAccountCode,
                        Collectors.reducing(BigDecimal.ZERO, amount, BigDecimal::add)));
    }

    private Map<String, LedgerAccount> accountsByCode() {
        return ledgerAccountRepository.findAll().stream()
                .collect(Collectors.toMap(LedgerAccount::getCode, Function.identity(), (a, b) -> a));
    }

    private static BigDecimal scale(BigDecimal value) {
        return (value != null ? value : BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }
}
