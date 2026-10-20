package com.voyra.crm.service;

import com.voyra.crm.dto.BankAccountRequest;
import com.voyra.crm.dto.BankMatchRuleRequest;
import com.voyra.crm.dto.BankStatementProfileRequest;
import com.voyra.crm.entity.BankAccount;
import com.voyra.crm.entity.BankMatchRule;
import com.voyra.crm.entity.BankStatementProfile;
import com.voyra.crm.entity.LedgerAccount;
import com.voyra.crm.enums.BankAmountConvention;
import com.voyra.crm.enums.BankMatchField;
import com.voyra.crm.enums.BankTransactionMatchStatus;
import com.voyra.crm.enums.LedgerAccountType;
import com.voyra.crm.repository.BankAccountRepository;
import com.voyra.crm.repository.BankMatchRuleRepository;
import com.voyra.crm.repository.BankStatementProfileRepository;
import com.voyra.crm.repository.BankTransactionRepository;
import com.voyra.crm.repository.LedgerAccountRepository;
import com.voyra.crm.repository.PaymentReceiptRepository;
import com.voyra.crm.repository.SupplierPaymentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** BUG-CRX-22: bank accounts, match rules and statement profiles are validated up front and can be edited and deleted. */
@ExtendWith(MockitoExtension.class)
class BankingSetupServiceTest {

    @Mock private BankAccountRepository bankAccountRepository;
    @Mock private BankMatchRuleRepository bankMatchRuleRepository;
    @Mock private BankStatementProfileRepository bankStatementProfileRepository;
    @Mock private BankTransactionRepository bankTransactionRepository;
    @Mock private LedgerAccountRepository ledgerAccountRepository;
    @Mock private PaymentReceiptRepository paymentReceiptRepository;
    @Mock private SupplierPaymentRepository supplierPaymentRepository;

    private BankAccountService accounts() {
        return new BankAccountService(bankAccountRepository, ledgerAccountRepository, bankTransactionRepository,
                paymentReceiptRepository, supplierPaymentRepository, new BaseCurrency(null));
    }

    private BankMatchRuleService rules() {
        return new BankMatchRuleService(bankMatchRuleRepository, ledgerAccountRepository);
    }

    private BankStatementProfileService profiles() {
        return new BankStatementProfileService(bankStatementProfileRepository);
    }

    private static LedgerAccount ledger(String code, String parent, boolean control) {
        return LedgerAccount.builder().id(code).code(code).name("A " + code).parentCode(parent)
                .accountType(LedgerAccountType.ASSET).isControl(control).isActive(true).build();
    }

    private static BankAccountRequest accountRequest(String ledgerCode) {
        BankAccountRequest r = new BankAccountRequest();
        r.setAccountName("CRX Test Bank");
        r.setAccountNumber("123");
        r.setLedgerAccountCode(ledgerCode);
        return r;
    }

    @Test
    void aBankAccountOnAnUnknownLedgerCodeIsRefused() {
        when(ledgerAccountRepository.findByCode("9999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accounts().create(accountRequest("9999")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("9999");
        verify(bankAccountRepository, never()).save(any());
    }

    @Test
    void aBankAccountMustMapToABankOrCashLedger() {
        when(ledgerAccountRepository.findByCode("5620")).thenReturn(Optional.of(ledger("5620", null, false)));
        assertThatThrownBy(() -> accounts().create(accountRequest("5620")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bank or cash");

        when(ledgerAccountRepository.findByCode("1111")).thenReturn(Optional.of(ledger("1111", "1110", false)));
        assertThat(accounts().create(accountRequest("1111")).getLedgerAccountCode()).isEqualTo("1111");
    }

    @Test
    void aBankAccountCanBeEditedAndDeletedUntilItHasPostedLines() {
        BankAccount account = BankAccount.builder().id("BA1").accountName("CRX Test Bank").ledgerAccountCode("9999").build();
        when(bankAccountRepository.findById("BA1")).thenReturn(Optional.of(account));
        when(ledgerAccountRepository.findByCode("1110")).thenReturn(Optional.of(ledger("1110", null, false)));

        assertThat(accounts().update("BA1", accountRequest("1110")).getLedgerAccountCode()).isEqualTo("1110");

        accounts().delete("BA1");
        verify(bankAccountRepository).delete(account);

        when(bankTransactionRepository.existsByBankAccountIdAndMatchStatusNot("BA1", BankTransactionMatchStatus.UNMATCHED)).thenReturn(true);
        assertThatThrownBy(() -> accounts().delete("BA1"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("posted");
    }

    @Test
    void aRuleNeedsAnExistingNonControlTargetAndANonNegativePriority() {
        BankMatchRuleRequest r = new BankMatchRuleRequest();
        r.setName("CRX rule");
        r.setMatchField(BankMatchField.DESCRIPTION);
        r.setPattern("CRX test");
        r.setTargetAccountCode("9999");
        r.setPriority(10);
        when(ledgerAccountRepository.findByCode("9999")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> rules().create(r)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("9999");

        r.setTargetAccountCode("1200");
        when(ledgerAccountRepository.findByCode("1200")).thenReturn(Optional.of(ledger("1200", null, true)));
        assertThatThrownBy(() -> rules().create(r)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("control");

        r.setTargetAccountCode("5620");
        r.setPriority(-5);
        when(ledgerAccountRepository.findByCode("5620")).thenReturn(Optional.of(ledger("5620", null, false)));
        assertThatThrownBy(() -> rules().create(r)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Priority");

        BankMatchRule rule = BankMatchRule.builder().id("R1").name("CRX rule").targetAccountCode("9999").priority(-5).build();
        when(bankMatchRuleRepository.findById("R1")).thenReturn(Optional.of(rule));
        r.setPriority(5);
        assertThat(rules().update("R1", r).getTargetAccountCode()).isEqualTo("5620");
        rules().delete("R1");
        verify(bankMatchRuleRepository).delete(rule);
    }

    @Test
    void aProfileNeedsJsonColumnsForItsConventionAndAValidDateFormat() {
        BankStatementProfileRequest r = new BankStatementProfileRequest();
        r.setBankName("CRX Bank Profile");
        r.setDateFormat("yyyy-MM-dd");
        r.setAmountConvention(BankAmountConvention.DEBIT_CREDIT_COLUMNS);
        r.setColumnMap("{not json");
        assertThatThrownBy(() -> profiles().create(r)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("JSON");

        r.setColumnMap("{\"date\":\"Date\",\"amount\":\"Amount\"}");
        assertThatThrownBy(() -> profiles().create(r)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("debit");

        r.setColumnMap("{\"date\":\"Date\",\"debit\":\"Debit\",\"credit\":\"Credit\"}");
        r.setDateFormat("qq-bad-{");
        assertThatThrownBy(() -> profiles().create(r)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("date format");

        r.setDateFormat("dd/MM/yyyy");
        BankStatementProfile profile = BankStatementProfile.builder().id("P1").bankName("CRX Bank Profile").build();
        when(bankStatementProfileRepository.findById("P1")).thenReturn(Optional.of(profile));
        assertThat(profiles().update("P1", r).getDateFormat()).isEqualTo("dd/MM/yyyy");
        profiles().delete("P1");
        verify(bankStatementProfileRepository).delete(profile);
    }
}
