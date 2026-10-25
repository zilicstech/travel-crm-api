package com.voyra.crm.repository;

import com.voyra.crm.entity.PlatformAuditLog;
import com.voyra.crm.enums.PlatformAuditAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlatformAuditLogRepository extends JpaRepository<PlatformAuditLog, String> {

    List<PlatformAuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, String entityId);

    Optional<PlatformAuditLog> findFirstByActionAndEntityIdOrderByCreatedAtAsc(PlatformAuditAction action, String entityId);
}
