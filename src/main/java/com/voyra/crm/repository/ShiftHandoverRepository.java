package com.voyra.crm.repository;

import com.voyra.crm.entity.ShiftHandover;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ShiftHandoverRepository extends JpaRepository<ShiftHandover, String> {

    /** Whole-team notes (null toAgentId) plus ones addressed to this agent specifically. */
    List<ShiftHandover> findByToAgentIdOrToAgentIdIsNullOrderByShiftEndedAtDesc(String toAgentId);

    List<ShiftHandover> findAllByOrderByShiftEndedAtDesc();
}
