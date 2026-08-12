package com.voyra.crm.repository;

import com.voyra.crm.entity.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TenantRepository extends JpaRepository<Tenant, String> {

    Optional<Tenant> findByOwnerEmailIgnoreCase(String ownerEmail);

    boolean existsByOwnerEmailIgnoreCase(String ownerEmail);
}
