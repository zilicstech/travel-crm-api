package com.voyra.crm.service;

import com.voyra.crm.entity.PlatformAuditLog;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.repository.PlatformAuditLogRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/** Writes the platform audit trail. Actor comes straight from the principal: super admins have no tenant. */
@Service
@RequiredArgsConstructor
public class PlatformAuditService {

    private final PlatformAuditLogRepository repository;

    /** For scheduled jobs: there is no signed-in user, so the actor is SYSTEM. */
    @Transactional
    public void recordSystem(PlatformAuditAction action, String entityType, String entityId, Map<String, Object> detail) {
        repository.save(PlatformAuditLog.builder()
                .id(UniqueIdResolver.resolve(repository::existsById))
                .actorId("SYSTEM")
                .actorName("System")
                .action(action)
                .entityType(entityType)
                .entityId(entityId)
                .detail(detail)
                .build());
    }

    @Transactional
    public void record(PlatformAuditAction action, String entityType, String entityId, Map<String, Object> detail) {
        CustomUserPrincipal actor = SecurityContextUtil.getCurrentUserOrThrow();
        repository.save(PlatformAuditLog.builder()
                .id(UniqueIdResolver.resolve(repository::existsById))
                .actorId(actor.userId())
                .actorName(actor.username())
                .action(action)
                .entityType(entityType)
                .entityId(entityId)
                .detail(detail)
                .build());
    }
}
