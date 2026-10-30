package com.voyra.crm.repository;

import com.voyra.crm.entity.PlatformSupplierCredential;
import com.voyra.crm.enums.SupplierProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlatformSupplierCredentialRepository extends JpaRepository<PlatformSupplierCredential, String> {

    Optional<PlatformSupplierCredential> findByProviderAndIsActiveTrue(SupplierProvider provider);

    boolean existsByProvider(SupplierProvider provider);

    List<PlatformSupplierCredential> findAllByOrderByNameAsc();
}
