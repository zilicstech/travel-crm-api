package com.voyra.crm.repository;

import com.voyra.crm.entity.TaxRateConfig;
import com.voyra.crm.enums.SupplyNature;
import com.voyra.crm.enums.TaxKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TaxRateConfigRepository extends JpaRepository<TaxRateConfig, String> {

    List<TaxRateConfig> findAllByOrderByTaxKindAscSupplyNatureAscEffectiveFromDesc();

    List<TaxRateConfig> findByTaxKindOrderBySupplyNatureAscEffectiveFromDesc(TaxKind taxKind);

    /** The row TaxEngine falls back to when no specific override is chosen - enforced unique by the DB. */
    Optional<TaxRateConfig> findByTaxKindAndSupplyNatureAndIsDefaultTrueAndIsActiveTrue(
            TaxKind taxKind, SupplyNature supplyNature);
}
