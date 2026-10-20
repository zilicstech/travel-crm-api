package com.voyra.crm.service;

import com.voyra.crm.dto.BankAccountRequest;
import com.voyra.crm.dto.BankAccountResponse;
import com.voyra.crm.entity.BankAccount;
import com.voyra.crm.entity.LedgerAccount;
import com.voyra.crm.enums.BankTransactionMatchStatus;
import com.voyra.crm.enums.SystemAccount;
import com.voyra.crm.repository.BankAccountRepository;
import com.voyra.crm.repository.BankTransactionRepository;
import com.voyra.crm.repository.LedgerAccountRepository;
import com.voyra.crm.repository.PaymentReceiptRepository;
import com.voyra.crm.repository.SupplierPaymentRepository;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class BankAccountService {

    private static final Set<String> BANK_AND_CASH = Set.of(SystemAccount.BANK_ACCOUNTS.code(), SystemAccount.CASH_IN_HAND.code());

    private final BankAccountRepository bankAccountRepository;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final BankTransactionRepository bankTransactionRepository;
    private final PaymentReceiptRepository paymentReceiptRepository;
    private final SupplierPaymentRepository supplierPaymentRepository;
    private final BaseCurrency baseCurrency;

    @Transactional
    public BankAccountResponse create(BankAccountRequest request) {
        assertBankOrCashLedger(request.getLedgerAccountCode());
        BankAccount account = BankAccount.builder()
                .id(UniqueIdResolver.resolve(bankAccountRepository::existsById))
                .isActive(true)
                .createdDate(LocalDateTime.now())
                .build();
        apply(account, request);
        bankAccountRepository.save(account);
        return toResponse(account);
    }

    @Transactional
    public BankAccountResponse update(String id, BankAccountRequest request) {
        BankAccount account = find(id);
        assertBankOrCashLedger(request.getLedgerAccountCode());
        if (!account.getLedgerAccountCode().equals(request.getLedgerAccountCode()) && hasPostedLines(id)) {
            throw new IllegalStateException("This account already has posted bank lines, so its ledger account cannot change");
        }
        apply(account, request);
        bankAccountRepository.save(account);
        return toResponse(account);
    }

    /** Soft delete - only while nothing has been posted through the account; its unmatched lines go with it. */
    @Transactional
    public void delete(String id) {
        BankAccount account = find(id);
        if (hasPostedLines(id)) {
            throw new IllegalStateException("This account has posted bank lines or payments against it and cannot be deleted");
        }
        bankAccountRepository.delete(account);
    }

    @Transactional(readOnly = true)
    public List<BankAccountResponse> list() {
        return bankAccountRepository.findAll().stream().map(BankAccountService::toResponse).toList();
    }

    private boolean hasPostedLines(String id) {
        return bankTransactionRepository.existsByBankAccountIdAndMatchStatusNot(id, BankTransactionMatchStatus.UNMATCHED)
                || paymentReceiptRepository.existsByBankAccountId(id)
                || supplierPaymentRepository.existsByBankAccountId(id);
    }

    private void assertBankOrCashLedger(String code) {
        LedgerAccount ledger = ledgerAccountRepository.findByCode(code)
                .filter(a -> !Boolean.FALSE.equals(a.getIsActive()))
                .orElseThrow(() -> new IllegalArgumentException("Ledger account " + code + " does not exist in the chart of accounts"));
        if (!BANK_AND_CASH.contains(ledger.getCode()) && (ledger.getParentCode() == null || !BANK_AND_CASH.contains(ledger.getParentCode()))) {
            throw new IllegalArgumentException("Ledger account " + code + " (" + ledger.getName() + ") is not a bank or cash account - "
                    + "use 1110 Bank Accounts, 1100 Cash in Hand or an account under them");
        }
    }

    private void apply(BankAccount account, BankAccountRequest request) {
        account.setAccountName(request.getAccountName());
        account.setAccountNumber(request.getAccountNumber());
        account.setIfsc(request.getIfsc());
        account.setBranch(request.getBranch());
        account.setCurrencyCode(request.getCurrencyCode() != null ? request.getCurrencyCode() : baseCurrency.code());
        account.setLedgerAccountCode(request.getLedgerAccountCode());
        account.setOpeningBalance(request.getOpeningBalance() != null ? request.getOpeningBalance() : BigDecimal.ZERO);
    }

    private BankAccount find(String id) {
        return bankAccountRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Bank account not found: " + id));
    }

    private static BankAccountResponse toResponse(BankAccount a) {
        return BankAccountResponse.builder()
                .id(a.getId()).accountName(a.getAccountName()).accountNumber(a.getAccountNumber())
                .ifsc(a.getIfsc()).branch(a.getBranch()).currencyCode(a.getCurrencyCode())
                .ledgerAccountCode(a.getLedgerAccountCode()).openingBalance(a.getOpeningBalance())
                .isActive(a.getIsActive())
                .build();
    }
}
