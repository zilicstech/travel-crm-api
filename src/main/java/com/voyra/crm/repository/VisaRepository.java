package com.voyra.crm.repository;

import com.voyra.crm.entity.Visa;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface VisaRepository extends JpaRepository<Visa, String> {

    List<Visa> findByAgentId(String agentId);

    Page<Visa> findByAgentId(String agentId, Pageable pageable);

    List<Visa> findByClientId(String clientId);

    /** Agency-wide calendar feed. */
    List<Visa> findByAppointmentDateBetween(LocalDate from, LocalDate to);

    List<Visa> findByAgentIdAndAppointmentDateBetween(String agentId, LocalDate from, LocalDate to);

    @Modifying
    @Query("UPDATE Visa v SET v.agentName = :name WHERE v.agentId = :agentId")
    void updateAgentNameForAgent(@Param("agentId") String agentId, @Param("name") String name);

    @Modifying
    @Query("UPDATE Visa v SET v.clientName = :name WHERE v.clientId = :clientId")
    void updateClientNameForClient(@Param("clientId") String clientId, @Param("name") String name);
}
