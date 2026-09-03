package com.voyra.crm.repository;

import com.voyra.crm.entity.LeadService;
import com.voyra.crm.enums.ServiceStatus;
import com.voyra.crm.enums.ServiceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface LeadServiceRepository extends JpaRepository<LeadService, String> {

    List<LeadService> findByLeadIdOrderBySortOrderAsc(String leadId);

    List<LeadService> findByLeadIdInOrderBySortOrderAsc(Collection<String> leadIds);

    long countByLeadId(String leadId);

    /** Cross-lead board, scoped to the types an agent manages. */
    List<LeadService> findByTypeInOrderByCreatedAtDesc(Collection<ServiceType> types);

    List<LeadService> findByTypeInAndStatusOrderByCreatedAtDesc(Collection<ServiceType> types, ServiceStatus status);

    /** Keeps the denormalized client_name snapshot live-synced on Client rename. */
    @Modifying
    @Query("UPDATE LeadService s SET s.clientName = :name WHERE s.clientId = :clientId")
    void updateClientNameForClient(@Param("clientId") String clientId, @Param("name") String name);

    /** Keeps the denormalized assigned_agent_name snapshot live-synced on Agent rename. */
    @Modifying
    @Query("UPDATE LeadService s SET s.assignedAgentName = :name WHERE s.assignedAgentId = :agentId")
    void updateAssignedAgentNameForAgent(@Param("agentId") String agentId, @Param("name") String name);
}
