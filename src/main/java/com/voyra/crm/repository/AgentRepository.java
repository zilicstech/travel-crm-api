package com.voyra.crm.repository;

import com.voyra.crm.entity.Agent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AgentRepository extends JpaRepository<Agent, String> {

    Optional<Agent> findByEmailIgnoreCase(String email);

    List<Agent> findByTenantId(String tenantId);

    long countByTenantId(String tenantId);

    Optional<Agent> findByIdAndTenantId(String id, String tenantId);

    boolean existsByEmailIgnoreCase(String email);
}
