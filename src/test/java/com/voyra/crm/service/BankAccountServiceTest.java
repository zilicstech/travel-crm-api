package com.voyra.crm.service;

import com.voyra.crm.dto.BankAccountRequest;
import com.voyra.crm.dto.BankAccountResponse;
import com.voyra.crm.entity.BankAccount;
import com.voyra.crm.entity.LedgerAccount;
import com.voyra.crm.enums.BankTransactionMatchStatus;
import com.voyra.crm.repository.BankAccountRepository;
import com.voyra.crm.repository.BankTransactionRepository;
import com.voyra.crm.repository.LedgerAccountRepository;
import com.voyra.crm.repository.PaymentReceiptRepository;
import com.voyra.crm.repository.SupplierPaymentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BankAccountServiceTest {

    @Mock BankAccountRepository bankAccountRepository;
    @Mock LedgerAccountRepository ledgerAccountRepository;
    @Mock BankTransactionRepository bankTransactionRepository;
    @Mock PaymentReceiptRepository paymentReceiptRepository;
    @Mock SupplierPaymentRepository supplierPaymentRepository;
    @Spy BaseCurrency baseCurrency = new BaseCurrency(null);
    @InjectMocks BankAccountService service;

    private static BankAccountRequest req(String ledger) {
        BankAccountRequest r = new BankAccountRequest();
        r.setAccountName("HDFC Current");
        r.setAccountNumber("000111");
        r.setIfsc("HDFC0000001");
        r.setBranch("Fort");
        r.setLedgerAccountCode(ledger);
        return r;
    }

    private void ledger(String code, String parent, Boolean active) {
        when(ledgerAccountRepository.findByCode(code)).thenReturn(Optional.of(
                LedgerAccount.builder().code(code).name("Acc " + code).parentCode(parent).isActive(active).build()));
    }

    @Test
    void createsAnActiveAccountInBaseCurrencyWithAZeroOpeningBalance() {
        ledger("1110", null, true);

        BankAccountResponse res = service.create(req("1110"));

        ArgumentCaptor<BankAccount> saved = ArgumentCaptor.forClass(BankAccount.class);
        verify(bankAccountRepository).save(saved.capture());
        assertThat(saved.getValue().getIsActive()).isTrue();
        assertThat(saved.getValue().getCreatedDate()).isNotNull();
        assertThat(res.getCurrencyCode()).isEqualTo("INR");
        assertThat(res.getOpeningBalance()).isEqualByComparingTo("0");
        assertThat(res.getAccountName()).isEqualTo("HDFC Current");
        assertThat(res.getIfsc()).isEqualTo("HDFC0000001");
        assertThat(res.getBranch()).isEqualTo("Fort");
        assertThat(res.getLedgerAccountCode()).isEqualTo("1110");
    }

    @Test
    void anAccountUnderBankOrCashWithItsOwnCurrencyAndBalanceIsAccepted() {
        ledger("1111", "1110", null);
        BankAccountRequest r = req("1111");
        r.setCurrencyCode("USD");
        r.setOpeningBalance(new BigDecimal("2500.00"));

        BankAccountResponse res = service.create(r);

        assertThat(res.getCurrencyCode()).isEqualTo("USD");
        assertThat(res.getOpeningBalance()).isEqualByComparingTo("2500.00");
    }

    @Test
    void aMissingInactiveOrNonCashLedgerIsRefused() {
        when(ledgerAccountRepository.findByCode("9999")).thenReturn(Optional.empty());
        ledger("1112", "1110", false);
        ledger("4010", null, true);
        ledger("4011", "4010", true);

        assertThatThrownBy(() -> service.create(req("9999"))).hasMessage("Ledger account 9999 does not exist in the chart of accounts");
        assertThatThrownBy(() -> service.create(req("1112"))).hasMessage("Ledger account 1112 does not exist in the chart of accounts");
        assertThatThrownBy(() -> service.create(req("4010"))).hasMessageStartingWith("Ledger account 4010 (Acc 4010) is not a bank or cash account");
        assertThatThrownBy(() -> service.create(req("4011"))).hasMessageStartingWith("Ledger account 4011 (Acc 4011) is not a bank or cash account");
        verify(bankAccountRepository, never()).save(any());
    }

    @Test
    void theLedgerCanChangeOnlyWhileNothingIsPosted() {
        BankAccount account = BankAccount.builder().id("BA1").ledgerAccountCode("1110").build();
        when(bankAccountRepository.findById("BA1")).thenReturn(Optional.of(account));
        ledger("1100", null, true);
        when(bankTransactionRepository.existsByBankAccountIdAndMatchStatusNot("BA1", BankTransactionMatchStatus.UNMATCHED)).thenReturn(false, true);
        when(paymentReceiptRepository.existsByBankAccountId("BA1")).thenReturn(false);
        when(supplierPaymentRepository.existsByBankAccountId("BA1")).thenReturn(false);

        assertThat(service.update("BA1", req("1100")).getLedgerAccountCode()).isEqualTo("1100");
        account.setLedgerAccountCode("1110");
        assertThatThrownBy(() -> service.update("BA1", req("1100")))
                .hasMessage("This account already has posted bank lines, so its ledger account cannot change");
    }

    @Test
    void anUpdateKeepingTheLedgerNeedsNoPostingCheck() {
        BankAccount account = BankAccount.builder().id("BA1").ledgerAccountCode("1110").build();
        when(bankAccountRepository.findById("BA1")).thenReturn(Optional.of(account));
        ledger("1110", null, true);
        BankAccountRequest r = req("1110");
        r.setAccountName("HDFC Savings");

        service.update("BA1", r);

        assertThat(account.getAccountName()).isEqualTo("HDFC Savings");
        verify(bankTransactionRepository, never()).existsByBankAccountIdAndMatchStatusNot(any(), any());
    }

    @Test
    void anAccountIsDeletedOnlyWithNoPostedLinesReceiptsOrPayments() {
        BankAccount account = BankAccount.builder().id("BA1").ledgerAccountCode("1110").build();
        when(bankAccountRepository.findById("BA1")).thenReturn(Optional.of(account));
        when(bankAccountRepository.findById("BA9")).thenReturn(Optional.empty());
        when(bankTransactionRepository.existsByBankAccountIdAndMatchStatusNot("BA1", BankTransactionMatchStatus.UNMATCHED)).thenReturn(false);
        when(paymentReceiptRepository.existsByBankAccountId("BA1")).thenReturn(true, false, false);
        when(supplierPaymentRepository.existsByBankAccountId("BA1")).thenReturn(true, false);

        assertThatThrownBy(() -> service.delete("BA1")).hasMessageStartingWith("This account has posted bank lines or payments");
        assertThatThrownBy(() -> service.delete("BA1")).hasMessageStartingWith("This account has posted bank lines or payments");
        service.delete("BA1");
        verify(bankAccountRepository).delete(account);
        assertThatThrownBy(() -> service.delete("BA9")).hasMessage("Bank account not found: BA9");
    }

    @Test
    void listsEveryAccount() {
        when(bankAccountRepository.findAll()).thenReturn(List.of(BankAccount.builder().id("BA1").accountName("HDFC").isActive(true).build()));

        assertThat(service.list()).singleElement().satisfies(a -> {
            assertThat(a.getId()).isEqualTo("BA1");
            assertThat(a.getIsActive()).isTrue();
        });
    }
}
