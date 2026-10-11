package com.voyra.crm.service;

import com.voyra.crm.dto.BankAccountRequest;
import com.voyra.crm.dto.BankAccountResponse;
import com.voyra.crm.entity.BankAccount;
import com.voyra.crm.repository.BankAccountRepository;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BankAccountService {

    private final BankAccountRepository bankAccountRepository;

    @Transactional
    public BankAccountResponse create(BankAccountRequest request) {
        BankAccount account = BankAccount.builder()
                .id(UniqueIdResolver.resolve(bankAccountRepository::existsById))
                .accountName(request.getAccountName())
                .accountNumber(request.getAccountNumber())
                .ifsc(request.getIfsc())
                .branch(request.getBranch())
                .currencyCode(request.getCurrencyCode() != null ? request.getCurrencyCode() : "INR")
                .ledgerAccountCode(request.getLedgerAccountCode())
                .openingBalance(request.getOpeningBalance() != null ? request.getOpeningBalance() : BigDecimal.ZERO)
                .isActive(true)
                .createdDate(LocalDateTime.now())
                .build();
        bankAccountRepository.save(account);
        return toResponse(account);
    }

    @Transactional(readOnly = true)
    public List<BankAccountResponse> list() {
        return bankAccountRepository.findAll().stream().map(BankAccountService::toResponse).toList();
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
