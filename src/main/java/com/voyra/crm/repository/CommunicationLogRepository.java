package com.voyra.crm.repository;

import com.voyra.crm.entity.CommunicationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CommunicationLogRepository extends JpaRepository<CommunicationLog, String> {

    List<CommunicationLog> findByClientIdOrderByOccurredAtDesc(String clientId);

    List<CommunicationLog> findByLeadIdOrderByOccurredAtDesc(String leadId);

    Optional<CommunicationLog> findByIdAndClientId(String id, String clientId);
}
