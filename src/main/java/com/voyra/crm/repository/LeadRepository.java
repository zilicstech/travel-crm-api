package com.voyra.crm.repository;

import com.voyra.crm.entity.Lead;
import com.voyra.crm.enums.LeadStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface LeadRepository extends JpaRepository<Lead, String> {

    List<Lead> findByAssignedTo(String assignedTo);

    List<Lead> findByCustomerId(String customerId);

    long countByCustomerIdAndStatusNotIn(String customerId, Collection<LeadStatus> excludedStatuses);

    List<Lead> findByStatus(LeadStatus status);

    Optional<Lead> findByPublicProposalToken(String publicProposalToken);

    long countByAssignedTo(String assignedTo);

    long countByStatus(LeadStatus status);

    long countByFollowUpDateLessThanEqualAndStatusNotIn(LocalDate date, Collection<LeadStatus> excludedStatuses);

    long countByAssignedToAndStatus(String assignedTo, LeadStatus status);

    long countByAssignedToAndStatusIn(String assignedTo, Collection<LeadStatus> statuses);

    long countByAssignedToAndStatusNotIn(String assignedTo, Collection<LeadStatus> statuses);

    long countByAssignedToAndFollowUpDateLessThanEqualAndStatusNotIn(
            String assignedTo, LocalDate date, Collection<LeadStatus> excludedStatuses);

    boolean existsByAssignedToAndStatusNotIn(String assignedTo, Collection<LeadStatus> excludedStatuses);

    /** Keeps the denormalized assigned_agent_name snapshot live-synced on Agent rename. */
    @Modifying
    @Query("UPDATE Lead l SET l.assignedAgentName = :name WHERE l.assignedTo = :agentId")
    void updateAssignedAgentNameForAgent(@Param("agentId") String agentId, @Param("name") String name);
}
