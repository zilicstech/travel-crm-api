package com.voyra.crm.repository;

import com.voyra.crm.entity.PlatformAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PlatformAuditLogRepository extends JpaRepository<PlatformAuditLog, String> {

    List<PlatformAuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, String entityId);
}
