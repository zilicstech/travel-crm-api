package com.voyra.crm.repository;

import com.voyra.crm.entity.CancellationFollowUp;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CancellationFollowUpRepository extends JpaRepository<CancellationFollowUp, String> {

    List<CancellationFollowUp> findByBookingIdOrderByDueDateAscCreatedAtAsc(String bookingId);

    List<CancellationFollowUp> findByBookingIdInOrderByDueDateAscCreatedAtAsc(Collection<String> bookingIds);

    Optional<CancellationFollowUp> findByIdAndBookingId(String id, String bookingId);

    List<CancellationFollowUp> findByStatusAndDueDateLessThanOrderByDueDateAsc(com.voyra.crm.enums.FollowUpStatus status, java.time.LocalDate before);

    List<CancellationFollowUp> findByAssignedAgentIdAndStatusAndDueDateLessThanOrderByDueDateAsc(String agentId,
            com.voyra.crm.enums.FollowUpStatus status, java.time.LocalDate before);
}
