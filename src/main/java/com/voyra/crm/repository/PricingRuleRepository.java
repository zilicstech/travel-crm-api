package com.voyra.crm.repository;

import com.voyra.crm.entity.PricingRule;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.enums.PricingRuleKind;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PricingRuleRepository extends JpaRepository<PricingRule, String> {

    List<PricingRule> findByProduct(PricingProduct product);

    Optional<PricingRule> findByKindAndProductAndIsDefaultTrue(PricingRuleKind kind, PricingProduct product);

    boolean existsByKindAndProductAndNameIgnoreCase(PricingRuleKind kind, PricingProduct product, String name);

    boolean existsByKindAndProductAndNameIgnoreCaseAndIdNot(PricingRuleKind kind, PricingProduct product, String name, String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM PricingRule r WHERE r.id = :id")
    Optional<PricingRule> findByIdForUpdate(@Param("id") String id);
}
