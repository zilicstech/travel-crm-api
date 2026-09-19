package com.voyra.crm.repository;

import com.voyra.crm.entity.Agent;
import com.voyra.crm.enums.UserType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AgentRepository extends JpaRepository<Agent, String> {

    Optional<Agent> findByEmailIgnoreCase(String email);

    List<Agent> findByTenantId(String tenantId);

    Page<Agent> findByTenantId(String tenantId, Pageable pageable);

    List<Agent> findByTenantIdAndUserRole(String tenantId, UserType userRole);

    Page<Agent> findByTenantIdAndUserRole(String tenantId, UserType userRole, Pageable pageable);

    long countByTenantId(String tenantId);

    long countByTenantIdAndUserRole(String tenantId, UserType userRole);

    Optional<Agent> findByIdAndTenantId(String id, String tenantId);

    boolean existsByEmailIgnoreCase(String email);
}
