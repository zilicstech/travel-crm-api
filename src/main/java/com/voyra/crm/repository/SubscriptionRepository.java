package com.voyra.crm.repository;

import com.voyra.crm.entity.Subscription;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SubscriptionRepository extends JpaRepository<Subscription, String> {

    Optional<Subscription> findByTenantId(String tenantId);

    /** Serialises seat checks per tenant: hold the row lock until the surrounding transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Subscription s where s.tenantId = :tenantId")
    Optional<Subscription> findByTenantIdForUpdate(@Param("tenantId") String tenantId);
}
