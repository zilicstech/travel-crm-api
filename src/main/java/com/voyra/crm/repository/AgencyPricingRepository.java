package com.voyra.crm.repository;

import com.voyra.crm.entity.AgencyPricing;
import com.voyra.crm.enums.PricingProduct;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface AgencyPricingRepository extends JpaRepository<AgencyPricing, String> {

    List<AgencyPricing> findByTenantIdOrderByEffectiveFromDesc(String tenantId);

    List<AgencyPricing> findByTenantIdAndProductOrderByEffectiveFromDesc(String tenantId, PricingProduct product);

    boolean existsByTenantIdAndProductAndEffectiveFrom(String tenantId, PricingProduct product, Instant effectiveFrom);
}
