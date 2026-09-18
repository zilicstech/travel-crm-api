package com.voyra.crm.repository;

import com.voyra.crm.entity.LeadFollowUp;
import com.voyra.crm.enums.FollowUpStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface LeadFollowUpRepository extends JpaRepository<LeadFollowUp, String> {

    List<LeadFollowUp> findByLeadIdOrderByDueDateAsc(String leadId);

    /** Drives lead.follow_up_date - the earliest OPEN due date for this lead, or none. */
    Optional<LeadFollowUp> findFirstByLeadIdAndStatusOrderByDueDateAsc(String leadId, FollowUpStatus status);

    long countByLeadIdAndStatus(String leadId, FollowUpStatus status);

    List<LeadFollowUp> findByAssignedAgentIdOrderByDueDateAsc(String assignedAgentId);

    List<LeadFollowUp> findByAssignedAgentIdAndStatusOrderByDueDateAsc(String assignedAgentId, FollowUpStatus status);

    List<LeadFollowUp> findByAssignedAgentIdAndStatusAndDueDateLessThanEqualOrderByDueDateAsc(
            String assignedAgentId, FollowUpStatus status, LocalDate dueDate);

    Optional<LeadFollowUp> findByIdAndLeadId(String id, String leadId);

    /** Owner-wide escalation feed. Agent's own uses the assignedAgentId-scoped variant above. */
    List<LeadFollowUp> findByStatusAndDueDateLessThanOrderByDueDateAsc(FollowUpStatus status, LocalDate dueDate);

    List<LeadFollowUp> findByAssignedAgentIdAndStatusAndDueDateLessThanOrderByDueDateAsc(
            String assignedAgentId, FollowUpStatus status, LocalDate dueDate);
}
