package com.voyra.crm.service;

import com.voyra.crm.entity.BankAccount;
import com.voyra.crm.entity.BankStatementProfile;
import com.voyra.crm.entity.BankTransaction;
import com.voyra.crm.enums.BankAmountConvention;
import com.voyra.crm.enums.BankTransactionDirection;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.models.ParsedBankLine;
import com.voyra.crm.models.StatementParseResult;
import com.voyra.crm.repository.BankAccountRepository;
import com.voyra.crm.repository.BankStatementImportRepository;
import com.voyra.crm.repository.BankStatementProfileRepository;
import com.voyra.crm.repository.BankTransactionRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/** Rule 4.2.2 - duplicate protection is checked before persisting each row, not after the whole batch. */
@ExtendWith(MockitoExtension.class)
class BankStatementImportServiceTest {

    @Mock private BankAccountRepository bankAccountRepository;
    @Mock private BankStatementProfileRepository bankStatementProfileRepository;
    @Mock private BankStatementImportRepository bankStatementImportRepository;
    @Mock private BankTransactionRepository bankTransactionRepository;
    @Mock private StatementParser csvStatementParser;
    @Mock private FileStorageService fileStorageService;
    @Mock private BankMatchingService bankMatchingService;
    @Mock private AuditService auditService;

    @InjectMocks
    private BankStatementImportService importService;

    @BeforeEach
    void authenticateAsAccountant() {
        CustomUserPrincipal principal = new CustomUserPrincipal("AC1", "neha", UserType.ACCOUNTANT, "T1");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aLineAlreadyImportedForTheSameAccountDateAmountAndReferenceIsSkippedAsADuplicate() {
        BankAccount account = BankAccount.builder().id("BA1").accountName("HDFC Current").build();
        ParsedBankLine newLine = new ParsedBankLine(
                LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 21), "NEW", "REF2",
                new BigDecimal("1000.00"), BankTransactionDirection.CREDIT, null);
        ParsedBankLine duplicateLine = new ParsedBankLine(
                LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 20), "DUP", "REF1",
                new BigDecimal("500.00"), BankTransactionDirection.DEBIT, null);
        StatementParseResult parseResult = new StatementParseResult(List.of(duplicateLine, newLine), List.of());

        when(bankTransactionRepository.findByBankAccountIdAndTxnDateAndAmountAndBankReference(
                "BA1", duplicateLine.txnDate(), duplicateLine.amount(), duplicateLine.bankReference()))
                .thenReturn(Optional.of(BankTransaction.builder().id("EXISTING").build()));
        when(bankTransactionRepository.findByBankAccountIdAndTxnDateAndAmountAndBankReference(
                "BA1", newLine.txnDate(), newLine.amount(), newLine.bankReference()))
                .thenReturn(Optional.empty());
        when(bankMatchingService.runTier1(anyList())).thenReturn(0);

        var outcome = importService.persistAndMatch(account,
                BankStatementProfile.builder().id("P1").amountConvention(BankAmountConvention.SINGLE_SIGNED).build(),
                "statement.csv", "key123", parseResult);

        assertThat(outcome.rowsSaved()).isEqualTo(1);
        assertThat(outcome.duplicatesSkipped()).isEqualTo(1);
    }

    @Test
    void parseErrorsAreCarriedThroughToTheOutcomeNotSwallowed() {
        BankAccount account = BankAccount.builder().id("BA1").accountName("HDFC Current").build();
        StatementParseResult parseResult = new StatementParseResult(List.of(), List.of("Row 3: bad date"));
        when(bankMatchingService.runTier1(anyList())).thenReturn(0);

        var outcome = importService.persistAndMatch(account,
                BankStatementProfile.builder().id("P1").amountConvention(BankAmountConvention.SINGLE_SIGNED).build(),
                "statement.csv", "key123", parseResult);

        assertThat(outcome.parseErrors()).containsExactly("Row 3: bad date");
        assertThat(outcome.rowsSaved()).isZero();
    }

    @Test
    void onlyCsvProfilesAreAcceptedForImport() {
        when(bankAccountRepository.findById("BA1")).thenReturn(Optional.of(BankAccount.builder().id("BA1").build()));
        when(bankStatementProfileRepository.findById("P1")).thenReturn(Optional.of(
                BankStatementProfile.builder().id("P1").fileFormat("OFX").build()));
        org.springframework.mock.web.MockMultipartFile file = new org.springframework.mock.web.MockMultipartFile(
                "file", "statement.ofx", "text/plain", "irrelevant".getBytes());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> importService.importStatement("BA1", "P1", file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only CSV");
    }
}
