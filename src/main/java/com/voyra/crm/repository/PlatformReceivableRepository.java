package com.voyra.crm.repository;

import com.voyra.crm.entity.PlatformReceivable;
import com.voyra.crm.enums.ReceivableReason;
import com.voyra.crm.enums.ReceivableStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface PlatformReceivableRepository extends JpaRepository<PlatformReceivable, String> {

    List<PlatformReceivable> findByTenantIdAndStatusOrderByCreatedAt(String tenantId, ReceivableStatus status);

    boolean existsByTenantIdAndStatus(String tenantId, ReceivableStatus status);

    /** Original shortfalls opened against a reference, settled or not. */
    @Query("SELECT COALESCE(SUM(r.originalAmount), 0) FROM PlatformReceivable r WHERE r.reason = :reason AND r.refType = :refType AND r.refId = :refId")
    BigDecimal sumOriginalAmountByReasonAndRef(@Param("reason") ReceivableReason reason, @Param("refType") String refType,
                                               @Param("refId") String refId);
}
