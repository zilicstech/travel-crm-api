package com.voyra.crm.repository;

import com.voyra.crm.entity.ShiftHandover;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ShiftHandoverRepository extends JpaRepository<ShiftHandover, String> {

    /** Whole-team notes (null toAgentId), ones addressed to this agent, and ones this agent sent. */
    @Query("select h from ShiftHandover h where h.toAgentId = :agentId or h.toAgentId is null or h.fromAgentId = :agentId "
            + "order by h.shiftEndedAt desc")
    List<ShiftHandover> findVisibleToAgent(@Param("agentId") String agentId);

    List<ShiftHandover> findAllByOrderByShiftEndedAtDesc();
}
