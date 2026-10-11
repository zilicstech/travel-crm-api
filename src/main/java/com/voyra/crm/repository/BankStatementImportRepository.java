package com.voyra.crm.repository;

import com.voyra.crm.entity.BankStatementImport;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BankStatementImportRepository extends JpaRepository<BankStatementImport, String> {

    List<BankStatementImport> findByBankAccountId(String bankAccountId, Sort sort);
}
