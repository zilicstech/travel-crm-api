package com.voyra.crm.service;

import com.voyra.crm.entity.BankAccount;
import com.voyra.crm.entity.BankStatementImport;
import com.voyra.crm.entity.BankStatementProfile;
import com.voyra.crm.entity.BankTransaction;
import com.voyra.crm.enums.AuditEntityType;
import com.voyra.crm.enums.BankStatementImportStatus;
import com.voyra.crm.enums.BankTransactionMatchStatus;
import com.voyra.crm.models.ParsedBankLine;
import com.voyra.crm.models.StatementParseResult;
import com.voyra.crm.repository.BankAccountRepository;
import com.voyra.crm.repository.BankStatementImportRepository;
import com.voyra.crm.repository.BankStatementProfileRepository;
import com.voyra.crm.repository.BankTransactionRepository;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Rule 4.2 - parse-then-persist. {@link #importStatement} does the file store and the parse
 * OUTSIDE any transaction (blueprint §8.6); only {@link #persistAndMatch} is {@code
 * @Transactional}, and it is also where tier-1 matching runs (Rule 4.3) so a newly-imported line
 * that exactly matches an open document is posted in the same breath it is saved.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BankStatementImportService {

    private static final String FILE_CATEGORY = "bank-statements";

    private final BankAccountRepository bankAccountRepository;
    private final BankStatementProfileRepository bankStatementProfileRepository;
    private final BankStatementImportRepository bankStatementImportRepository;
    private final BankTransactionRepository bankTransactionRepository;
    private final StatementParser csvStatementParser;
    private final FileStorageService fileStorageService;
    private final BankMatchingService bankMatchingService;
    private final AuditService auditService;

    /** Not {@code @Transactional} - file storage and parsing are forbidden inside one (blueprint §8.6). */
    public BankImportOutcome importStatement(String bankAccountId, String profileId, MultipartFile file) {
        BankAccount bankAccount = bankAccountRepository.findById(bankAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Bank account not found: " + bankAccountId));
        BankStatementProfile profile = bankStatementProfileRepository.findById(profileId)
                .orElseThrow(() -> new IllegalArgumentException("Statement profile not found: " + profileId));
        if (!"CSV".equalsIgnoreCase(profile.getFileFormat())) {
            throw new IllegalArgumentException(
                    "Only CSV statement profiles are supported in this release - got '" + profile.getFileFormat() + "'");
        }

        String tenantId = SecurityContextUtil.getCurrentUserOrThrow().tenantId();
        String storageKey = fileStorageService.store(tenantId, FILE_CATEGORY, bankAccountId, file);

        byte[] content;
        try {
            content = file.getBytes();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        StatementParseResult parseResult = csvStatementParser.parse(content, profile);

        return persistAndMatch(bankAccount, profile, file.getOriginalFilename(), storageKey, parseResult);
    }

    @Transactional
    public BankImportOutcome persistAndMatch(BankAccount bankAccount, BankStatementProfile profile, String fileName,
                                              String storageKey, StatementParseResult parseResult) {
        String actor = currentUserId();
        BankStatementImport importRow = BankStatementImport.builder()
                .id(UniqueIdResolver.resolve(bankStatementImportRepository::existsById))
                .bankAccountId(bankAccount.getId())
                .profileId(profile.getId())
                .fileName(fileName)
                .storageKey(storageKey)
                .status(parseResult.rowErrors().isEmpty() ? BankStatementImportStatus.COMPLETED : BankStatementImportStatus.COMPLETED)
                .importedAt(LocalDateTime.now())
                .importedBy(actor)
                .build();

        int saved = 0;
        int duplicates = 0;
        List<BankTransaction> persisted = new ArrayList<>();
        for (ParsedBankLine line : parseResult.lines()) {
            Optional<BankTransaction> existing = bankTransactionRepository
                    .findByBankAccountIdAndTxnDateAndAmountAndBankReference(
                            bankAccount.getId(), line.txnDate(), line.amount(), line.bankReference());
            if (existing.isPresent()) {
                duplicates++;
                continue;
            }
            BankTransaction txn = BankTransaction.builder()
                    .id(UniqueIdResolver.resolve(bankTransactionRepository::existsById))
                    .importId(importRow.getId())
                    .bankAccountId(bankAccount.getId())
                    .txnDate(line.txnDate())
                    .valueDate(line.valueDate())
                    .description(line.description())
                    .bankReference(line.bankReference())
                    .amount(line.amount())
                    .direction(line.direction())
                    .runningBalance(line.runningBalance())
                    .matchStatus(BankTransactionMatchStatus.UNMATCHED)
                    .build();
            try {
                bankTransactionRepository.save(txn);
            } catch (DataIntegrityViolationException e) {
                // Belt-and-braces against the DB-level unique index (Rule 4.2.2) racing the
                // pre-check above under concurrent imports of overlapping periods.
                duplicates++;
                continue;
            }
            persisted.add(txn);
            saved++;
        }

        importRow.setRowCount(saved);
        importRow.setDuplicateCount(duplicates);
        bankStatementImportRepository.save(importRow);

        int tier1Matched = bankMatchingService.runTier1(persisted);

        auditService.recordCreate(AuditEntityType.BANK_STATEMENT_IMPORT, importRow.getId(),
                bankAccount.getAccountName() + " / " + fileName);
        log.info("Bank statement imported: id={}, bankAccountId={}, rows={}, duplicates={}, tier1Matched={}, parseErrors={}",
                importRow.getId(), bankAccount.getId(), saved, duplicates, tier1Matched, parseResult.rowErrors().size());

        return new BankImportOutcome(importRow.getId(), saved, duplicates, tier1Matched, parseResult.rowErrors());
    }

    private String currentUserId() {
        return SecurityContextUtil.getCurrentUserOrThrow().userId();
    }

    public record BankImportOutcome(
            String importId, int rowsSaved, int duplicatesSkipped, int tier1Matched, List<String> parseErrors) {
    }
}
