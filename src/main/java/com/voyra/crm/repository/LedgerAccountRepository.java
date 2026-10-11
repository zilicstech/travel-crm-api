package com.voyra.crm.repository;

import com.voyra.crm.entity.LedgerAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, String> {

    Optional<LedgerAccount> findByCode(String code);

    boolean existsByCode(String code);

    List<LedgerAccount> findByIsActiveTrue();
}
