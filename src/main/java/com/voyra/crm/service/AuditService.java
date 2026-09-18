package com.voyra.crm.service;

import com.voyra.crm.dto.AuditChange;
import com.voyra.crm.dto.AuditLogResponse;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.entity.AuditLog;
import com.voyra.crm.enums.AuditAction;
import com.voyra.crm.enums.AuditEntityType;
import com.voyra.crm.repository.AuditLogRepository;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes and reads the compliance-grade, entity-agnostic audit trail. Deliberately separate from
 * {@link LeadTimelineService}: that service writes user-facing narrative scoped to one lead
 * ("Note added by Liam"); this one records structured field-level {@code {field, old, new}}
 * changes across nine entity types and is owner-visible, not customer-visible. On a Lead write,
 * {@code LeadService} calls <b>both</b> - that duplication is intentional. Do not merge them:
 * merging is impossible in one direction (most of the audited entity types have no lead) and
 * wrong in the other (a vendor edit has no place in a 24-value lead event vocabulary).
 *
 * <p>Every {@code record*} method uses default (REQUIRED) propagation so it joins the caller's
 * already-open transaction: one physical transaction, joint commit, joint rollback. An audit
 * trail that can disagree with the data it audits is worse than none - see
 * {@link LeadTimelineService}'s class javadoc for the same rule stated about the timeline.
 * Deliberately not {@code REQUIRES_NEW} (reserved for the tenant-switch pattern, §8.6) and not an
 * after-commit event listener, which would decouple exactly the two things that must be atomic.
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final AuthorResolver authorResolver;

    @Transactional
    public void recordCreate(AuditEntityType type, String entityId, String entityLabel) {
        save(type, entityId, entityLabel, AuditAction.CREATE, List.of());
    }

    /** No-ops when changes is empty, so a PATCH that changed nothing leaves no row. */
    @Transactional
    public void recordUpdate(AuditEntityType type, String entityId, String entityLabel, List<AuditChange> changes) {
        if (changes.isEmpty()) {
            return;
        }
        save(type, entityId, entityLabel, AuditAction.UPDATE, changes);
    }

    /** finalSnapshot is stored so a genuinely removed record is still reconstructable. */
    @Transactional
    public void recordDelete(AuditEntityType type, String entityId, String entityLabel, List<AuditChange> finalSnapshot) {
        save(type, entityId, entityLabel, AuditAction.DELETE, finalSnapshot);
    }

    @Transactional(readOnly = true)
    public List<AuditLogResponse> listForRecord(AuditEntityType type, String entityId) {
        return auditLogRepository.findTop200ByEntityTypeAndEntityIdOrderByCreatedAtDesc(type, entityId).stream()
                .map(AuditService::toResponse)
                .toList();
    }

    /**
     * Filters are built as a Specification, adding a predicate only for what was actually
     * supplied - see {@link AuditLogRepository}'s javadoc for why a hand-written
     * {@code (:param IS NULL OR ...)} JPQL query is not used here.
     */
    @Transactional(readOnly = true)
    public PagedResponse<AuditLogResponse> search(AuditEntityType type, String actorId,
                                                   LocalDate from, LocalDate to, Pageable pageable) {
        LocalDateTime fromInclusive = from == null ? null : from.atStartOfDay();
        LocalDateTime toExclusive = to == null ? null : to.plusDays(1).atStartOfDay();

        List<Specification<AuditLog>> predicates = new ArrayList<>();
        if (type != null) {
            predicates.add((root, query, cb) -> cb.equal(root.get("entityType"), type));
        }
        if (actorId != null) {
            predicates.add((root, query, cb) -> cb.equal(root.get("actorId"), actorId));
        }
        if (fromInclusive != null) {
            predicates.add((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), fromInclusive));
        }
        if (toExclusive != null) {
            predicates.add((root, query, cb) -> cb.lessThan(root.get("createdAt"), toExclusive));
        }
        Specification<AuditLog> spec = Specification.allOf(predicates);
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        return PagedResponse.from(auditLogRepository.findAll(spec, sorted), AuditService::toResponse);
    }

    private void save(AuditEntityType type, String entityId, String entityLabel, AuditAction action, List<AuditChange> changes) {
        AuthorResolver.AuthorInfo actor = authorResolver.resolveCurrentAuthor();
        AuditLog entry = AuditLog.builder()
                .id(UniqueIdResolver.resolve(auditLogRepository::existsById))
                .entityType(type)
                .entityId(entityId)
                .entityLabel(entityLabel)
                .action(action)
                .actorId(actor.id())
                .actorName(actor.name())
                .fieldChanges(changes)
                .createdAt(LocalDateTime.now())
                .build();
        auditLogRepository.save(entry);
    }

    private static AuditLogResponse toResponse(AuditLog a) {
        return AuditLogResponse.builder()
                .id(a.getId())
                .entityType(a.getEntityType())
                .entityId(a.getEntityId())
                .entityLabel(a.getEntityLabel())
                .action(a.getAction())
                .actorId(a.getActorId())
                .actorName(a.getActorName())
                .changes(a.getFieldChanges())
                .createdAt(a.getCreatedAt())
                .build();
    }
}
