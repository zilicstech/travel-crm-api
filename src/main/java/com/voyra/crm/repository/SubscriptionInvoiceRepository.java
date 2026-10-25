package com.voyra.crm.repository;

import com.voyra.crm.entity.SubscriptionInvoice;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SubscriptionInvoiceRepository extends JpaRepository<SubscriptionInvoice, String> {

    List<SubscriptionInvoice> findByTenantIdAndStatusNotOrderByCreatedAtDesc(String tenantId, String status);

    List<SubscriptionInvoice> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    /** Serialises settlement: a webhook redelivery and an admin mark-paid on one invoice run one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from SubscriptionInvoice i where i.id = :id")
    Optional<SubscriptionInvoice> findByIdForUpdate(@Param("id") String id);

    boolean existsByTenantIdAndPeriodStart(String tenantId, Instant periodStart);

    boolean existsByTenantIdAndPeriodStartAndStatusIn(String tenantId, Instant periodStart, Collection<String> statuses);

    boolean existsByTenantIdAndStatusAndDueAtLessThanEqual(String tenantId, String status, Instant dueAt);

    Optional<SubscriptionInvoice> findFirstByTenantIdAndStatusOrderByDueAtAsc(String tenantId, String status);

    /** Only the key: a full save here could overwrite a settlement that committed after the invoice was read. */
    @Modifying
    @Query("update SubscriptionInvoice i set i.pdfKey = :key where i.id = :id")
    int setPdfKey(@Param("id") String id, @Param("key") String key);
}
