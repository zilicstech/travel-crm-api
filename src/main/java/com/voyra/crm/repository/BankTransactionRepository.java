package com.voyra.crm.repository;

import com.voyra.crm.entity.BankTransaction;
import com.voyra.crm.enums.BankTransactionMatchStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface BankTransactionRepository extends JpaRepository<BankTransaction, String> {

    List<BankTransaction> findByImportId(String importId);

    List<BankTransaction> findByBankAccountIdAndMatchStatus(String bankAccountId, BankTransactionMatchStatus matchStatus);

    Optional<BankTransaction> findByBankAccountIdAndTxnDateAndAmountAndBankReference(
            String bankAccountId, LocalDate txnDate, BigDecimal amount, String bankReference);
}
