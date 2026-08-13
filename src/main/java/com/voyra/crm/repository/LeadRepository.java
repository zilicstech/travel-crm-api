package com.voyra.crm.repository;

import com.voyra.crm.entity.Lead;
import com.voyra.crm.enums.LeadStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    List<Lead> findByAssignedToAndStatus(String assignedTo, LeadStatus status);

    Page<Lead> findByAssignedTo(String assignedTo, Pageable pageable);

    Page<Lead> findByStatus(LeadStatus status, Pageable pageable);

    Page<Lead> findByAssignedToAndStatus(String assignedTo, LeadStatus status, Pageable pageable);

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

    /** One grouped row per agent - replaces five per-agent count queries. */
    @Query("""
            SELECT l.assignedTo AS agentId,
                   COUNT(l) AS leadsAssigned,
                   SUM(CASE WHEN l.status = :booked THEN 1L ELSE 0L END) AS bookedLeads,
                   SUM(CASE WHEN l.status NOT IN :terminal THEN 1L ELSE 0L END) AS activeLeads,
                   SUM(CASE WHEN l.status IN :proposalStage THEN 1L ELSE 0L END) AS quotationsSent,
                   SUM(CASE WHEN l.followUpDate <= :today AND l.status NOT IN :terminal
                            THEN 1L ELSE 0L END) AS pendingFollowUps
            FROM Lead l
            WHERE l.assignedTo IN :agentIds
            GROUP BY l.assignedTo
            """)
    List<AgentLeadStatsProjection> aggregateLeadStatsByAgent(
            @Param("agentIds") Collection<String> agentIds,
            @Param("booked") LeadStatus booked,
            @Param("terminal") Collection<LeadStatus> terminal,
            @Param("proposalStage") Collection<LeadStatus> proposalStage,
            @Param("today") LocalDate today);

    interface AgentLeadStatsProjection {
        String getAgentId();
        long getLeadsAssigned();
        long getBookedLeads();
        long getActiveLeads();
        long getQuotationsSent();
        long getPendingFollowUps();
    }

    @Query("SELECT l.source AS category, COUNT(l) AS count FROM Lead l GROUP BY l.source")
    List<CategoryCountProjection> countGroupedBySource();

    @Query("SELECT l.status AS category, COUNT(l) AS count FROM Lead l WHERE l.status <> :excluded GROUP BY l.status")
    List<CategoryCountProjection> countGroupedByStatusExcluding(@Param("excluded") LeadStatus excluded);

    interface CategoryCountProjection {
        String getCategory();
        long getCount();
    }

    /** Keeps the denormalized assigned_agent_name snapshot live-synced on Agent rename. */
    @Modifying
    @Query("UPDATE Lead l SET l.assignedAgentName = :name WHERE l.assignedTo = :agentId")
    void updateAssignedAgentNameForAgent(@Param("agentId") String agentId, @Param("name") String name);
}
