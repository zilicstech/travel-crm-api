package com.voyra.crm.repository;

import com.voyra.crm.entity.AgencyPricingAssignment;
import com.voyra.crm.enums.PricingProduct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AgencyPricingAssignmentRepository extends JpaRepository<AgencyPricingAssignment, AgencyPricingAssignment.Key> {

    Optional<AgencyPricingAssignment> findByTenantIdAndProduct(String tenantId, PricingProduct product);

    List<AgencyPricingAssignment> findByTenantId(String tenantId);

    List<AgencyPricingAssignment> findByProduct(PricingProduct product);

    @Query("SELECT a FROM AgencyPricingAssignment a WHERE a.discountRuleId = :ruleId OR a.commissionRuleId = :ruleId")
    List<AgencyPricingAssignment> findUsing(@Param("ruleId") String ruleId);

    long countByProduct(PricingProduct product);
}
