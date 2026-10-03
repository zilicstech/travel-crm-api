package com.voyra.crm.service;

import com.voyra.crm.dto.AccountLedgerLineResponse;
import com.voyra.crm.dto.AccountLedgerResponse;
import com.voyra.crm.dto.JournalEntryResponse;
import com.voyra.crm.dto.LedgerAccountBalanceResponse;
import com.voyra.crm.entity.JournalEntry;
import com.voyra.crm.entity.JournalLine;
import com.voyra.crm.entity.LedgerAccount;
import com.voyra.crm.enums.JournalSourceType;
import com.voyra.crm.enums.LedgerAccountType;
import com.voyra.crm.repository.JournalEntryRepository;
import com.voyra.crm.repository.JournalLineRepository;
import com.voyra.crm.repository.LedgerAccountRepository;
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
 * Read-only views over the general ledger for the accounting screens - the chart of accounts
 * with balances, one account's General Ledger with a running balance, and the journal register.
 * Never writes; {@link JournalService} stays the single writer.
 *
 * <p>Every balance here is in the account's natural direction (debit minus credit for ASSET and
 * EXPENSE, credit minus debit otherwise), so a positive figure always reads as "normal" to an
 * accountant and a negative one flags an account running against its usual side.
 */
@Service
@RequiredArgsConstructor
public class GeneralLedgerQueryService {

    private final LedgerAccountRepository ledgerAccountRepository;
    private final JournalLineRepository journalLineRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final JournalService journalService;

    @Transactional(readOnly = true)
    public List<LedgerAccountBalanceResponse> chartOfAccounts(LocalDate asOf) {
        List<JournalLine> lines = journalLineRepository.findLedgerLinesAsOf(asOf);
        Map<String, BigDecimal> debitByAccount = sumByAccount(lines, JournalLine::getDebitAmountInr);
        Map<String, BigDecimal> creditByAccount = sumByAccount(lines, JournalLine::getCreditAmountInr);
        return ledgerAccountRepository.findAll().stream()
                .sorted(Comparator.comparing(LedgerAccount::getCode))
                .map(a -> {
                    BigDecimal debit = scale(debitByAccount.getOrDefault(a.getCode(), BigDecimal.ZERO));
                    BigDecimal credit = scale(creditByAccount.getOrDefault(a.getCode(), BigDecimal.ZERO));
                    return LedgerAccountBalanceResponse.builder()
                            .code(a.getCode()).name(a.getName()).accountType(a.getAccountType())
                            .parentCode(a.getParentCode()).isSystem(a.getIsSystem()).isControl(a.getIsControl())
                            .controlOf(a.getControlOf()).isActive(a.getIsActive())
                            .debitInr(debit).creditInr(credit)
                            .balanceInr(natural(a.getAccountType(), debit, credit))
                            .build();
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public AccountLedgerResponse accountLedger(String accountCode, LocalDate from, LocalDate to) {
        LedgerAccount account = ledgerAccountRepository.findByCode(accountCode)
                .orElseThrow(() -> new IllegalArgumentException("Ledger account not found: " + accountCode));
        LedgerAccountType type = account.getAccountType();

        BigDecimal opening = natural(type,
                journalLineRepository.sumDebitBefore(accountCode, from),
                journalLineRepository.sumCreditBefore(accountCode, from));

        List<JournalLine> lines = journalLineRepository.findAccountLinesBetween(accountCode, from, to);
        Map<String, JournalEntry> entriesById = lines.isEmpty() ? Map.of()
                : journalEntryRepository.findByIdIn(lines.stream().map(JournalLine::getJournalEntryId).distinct().toList())
                        .stream().collect(Collectors.toMap(JournalEntry::getId, Function.identity()));

        List<JournalLine> ordered = lines.stream()
                .sorted(Comparator
                        .comparing((JournalLine l) -> entriesById.get(l.getJournalEntryId()).getEntryDate())
                        .thenComparing(l -> entriesById.get(l.getJournalEntryId()).getEntryNumber())
                        .thenComparing(JournalLine::getLineNo))
                .toList();

        BigDecimal running = opening;
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        List<AccountLedgerLineResponse> rows = new ArrayList<>();
        for (JournalLine l : ordered) {
            JournalEntry e = entriesById.get(l.getJournalEntryId());
            BigDecimal debit = nz(l.getDebitAmountInr());
            BigDecimal credit = nz(l.getCreditAmountInr());
            totalDebit = totalDebit.add(debit);
            totalCredit = totalCredit.add(credit);
            running = running.add(natural(type, debit, credit));
            boolean foreign = l.getCurrencyCode() != null && !"INR".equals(l.getCurrencyCode());
            rows.add(AccountLedgerLineResponse.builder()
                    .journalEntryId(e.getId()).entryNumber(e.getEntryNumber()).entryDate(e.getEntryDate())
                    .sourceType(e.getSourceType()).sourceId(e.getSourceId()).purpose(e.getPurpose()).status(e.getStatus())
                    .narration(l.getNarration() != null && !l.getNarration().isBlank() ? l.getNarration() : e.getNarration())
                    .currencyCode(l.getCurrencyCode())
                    .foreignAmount(foreign ? nz(l.getDebitAmount()).add(nz(l.getCreditAmount())) : null)
                    .debitInr(scale(debit)).creditInr(scale(credit))
                    .runningBalanceInr(scale(running))
                    .build());
        }

        return AccountLedgerResponse.builder()
                .accountCode(account.getCode()).accountName(account.getName()).accountType(type)
                .isControl(account.getIsControl())
                .from(from).to(to)
                .openingBalanceInr(scale(opening))
                .totalDebitInr(scale(totalDebit)).totalCreditInr(scale(totalCredit))
                .closingBalanceInr(scale(running))
                .lines(rows)
                .build();
    }

    /** Journal register, newest first, optionally narrowed to one source document type. */
    @Transactional(readOnly = true)
    public List<JournalEntryResponse> journalRegister(LocalDate from, LocalDate to, JournalSourceType sourceType) {
        List<JournalEntry> entries = journalEntryRepository.findByEntryDateBetweenOrderByEntryDateDescEntryNumberDesc(from, to)
                .stream()
                .filter(e -> sourceType == null || e.getSourceType() == sourceType)
                .toList();
        return journalService.toResponses(entries);
    }

    static BigDecimal natural(LedgerAccountType type, BigDecimal debit, BigDecimal credit) {
        BigDecimal d = nz(debit);
        BigDecimal c = nz(credit);
        return (type == LedgerAccountType.ASSET || type == LedgerAccountType.EXPENSE) ? d.subtract(c) : c.subtract(d);
    }

    private static Map<String, BigDecimal> sumByAccount(List<JournalLine> lines, Function<JournalLine, BigDecimal> amount) {
        return lines.stream().collect(Collectors.groupingBy(JournalLine::getAccountCode,
                Collectors.reducing(BigDecimal.ZERO, l -> nz(amount.apply(l)), BigDecimal::add)));
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
