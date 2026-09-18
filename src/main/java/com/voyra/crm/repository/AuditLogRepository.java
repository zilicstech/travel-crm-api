package com.voyra.crm.repository;

import com.voyra.crm.entity.AuditLog;
import com.voyra.crm.enums.AuditEntityType;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.Repository;

import java.util.List;

/**
 * Extends the base {@code Repository} marker, not {@code JpaRepository} - delete/deleteAll/
 * deleteById are simply not on this bean, so no future service can remove an audit row even by
 * accident. Append-only is a property of the type, not a convention someone has to remember.
 * {@link JpaSpecificationExecutor} adds only find/count variants, no deletes, so it is safe to
 * add for the dynamic agency-wide search - see {@link com.voyra.crm.service.AuditService#search}.
 *
 * <p>The search filters are built as a {@code Specification} rather than a hand-written
 * {@code (:param IS NULL OR ...)} JPQL query: that pattern, combined with the LIMIT clause Spring
 * Data appends for {@code Pageable}, hits a real Postgres bug ("could not determine data type of
 * parameter") because a bind parameter used only in an {@code IS NULL} check has no column
 * context for Postgres to infer its type from. A Specification's predicates are only added for
 * filters that are actually supplied, so no such untyped parameter is ever sent.
 */
@org.springframework.stereotype.Repository
public interface AuditLogRepository extends Repository<AuditLog, String>, JpaSpecificationExecutor<AuditLog> {

    AuditLog save(AuditLog entry);

    boolean existsById(String id);

    List<AuditLog> findTop200ByEntityTypeAndEntityIdOrderByCreatedAtDesc(AuditEntityType entityType, String entityId);
}
