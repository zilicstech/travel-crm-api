package com.voyra.crm.repository;

import com.voyra.crm.entity.BankStatementProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BankStatementProfileRepository extends JpaRepository<BankStatementProfile, String> {
}
