package com.voyra.crm.repository;

import com.voyra.crm.entity.PlatformSupplierProduct;
import com.voyra.crm.enums.PricingProduct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface PlatformSupplierProductRepository extends JpaRepository<PlatformSupplierProduct, PlatformSupplierProduct.Key> {

    List<PlatformSupplierProduct> findByCredentialIdIn(Collection<String> credentialIds);

    Optional<PlatformSupplierProduct> findByCredentialIdAndProduct(String credentialId, PricingProduct product);
}
