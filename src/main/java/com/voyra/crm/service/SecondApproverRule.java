package com.voyra.crm.service;

import com.voyra.crm.entity.PlatformAuditLog;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.repository.PlatformAuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * The two-admin rule (tripjack_live_ready) must take two people, and an admin is shown the
 * password of every admin login they add. So the approver must be independent of the requester: neither login was added
 * by the other, directly or through admins they added (PLATFORM_ADMIN_CREATE in platform_audit_log), and the approver's
 * login existed before the request. The caller still refuses the requester approving their own request.
 */
@Component
@RequiredArgsConstructor
public class SecondApproverRule {

    private final PlatformAuditLogRepository auditLog;

    public void requireIndependent(String approverId, String requesterId, Instant requestedAt) {
        if (addedBy(approverId).contains(requesterId) || addedBy(requesterId).contains(approverId)) {
            throw new IllegalStateException("This needs an approver independent of the requester: an admin who added the requester's "
                    + "login, or whose login the requester added, cannot approve it - ask another platform admin");
        }
        if (creation(approverId).map(PlatformAuditLog::getCreatedAt).filter(at -> at.isAfter(requestedAt)).isPresent()) {
            throw new IllegalStateException("An admin whose login was added after this request was made cannot approve it - ask another platform admin");
        }
    }

    /** Everyone up the chain of who added this admin's login (the seeded admin has no create entry). */
    private Set<String> addedBy(String adminId) {
        Set<String> chain = new LinkedHashSet<>();
        Optional<PlatformAuditLog> step = creation(adminId);
        while (step.isPresent() && chain.add(step.get().getActorId())) {
            step = creation(step.get().getActorId());
        }
        return chain;
    }

    private Optional<PlatformAuditLog> creation(String adminId) {
        return auditLog.findFirstByActionAndEntityIdOrderByCreatedAtAsc(PlatformAuditAction.PLATFORM_ADMIN_CREATE, adminId);
    }
}
