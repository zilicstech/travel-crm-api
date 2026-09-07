package com.voyra.crm.repository;

import com.voyra.crm.entity.SupplierCredential;
import com.voyra.crm.enums.SupplierProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SupplierCredentialRepository extends JpaRepository<SupplierCredential, String> {

    Optional<SupplierCredential> findByProvider(SupplierProvider provider);
}
