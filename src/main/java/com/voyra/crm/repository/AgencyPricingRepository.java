package com.voyra.crm.repository;

import com.voyra.crm.entity.AgencyPricing;
import com.voyra.crm.enums.PricingProduct;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AgencyPricingRepository extends JpaRepository<AgencyPricing, String> {

    List<AgencyPricing> findByTenantIdOrderByEffectiveFromDesc(String tenantId);

    List<AgencyPricing> findByTenantIdAndProductOrderByEffectiveFromDesc(String tenantId, PricingProduct product);

    /** Row lock so two admins approving at once serialise; the second sees the first's approval. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM AgencyPricing p WHERE p.id = :id")
    Optional<AgencyPricing> findByIdForUpdate(@Param("id") String id);

    boolean existsByTenantIdAndProductAndEffectiveFrom(String tenantId, PricingProduct product, Instant effectiveFrom);
}
