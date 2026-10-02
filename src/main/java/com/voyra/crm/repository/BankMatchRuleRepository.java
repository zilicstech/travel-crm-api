package com.voyra.crm.repository;

import com.voyra.crm.entity.BankMatchRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BankMatchRuleRepository extends JpaRepository<BankMatchRule, String> {

    List<BankMatchRule> findByIsActiveTrueOrderByPriorityAsc();
}
