package com.voyra.crm.repository;

import com.voyra.crm.entity.PlatformSupplierCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PlatformSupplierCredentialRepository extends JpaRepository<PlatformSupplierCredential, String> {

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, String id);

    List<PlatformSupplierCredential> findAllByOrderByNameAsc();
}
