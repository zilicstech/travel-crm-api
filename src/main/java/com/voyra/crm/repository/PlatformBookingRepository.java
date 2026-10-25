package com.voyra.crm.repository;

import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.enums.PlatformBookingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PlatformBookingRepository extends JpaRepository<PlatformBooking, String> {

    /** Tenant-scoped lookup: another agency's id is simply absent (404). */
    Optional<PlatformBooking> findByIdAndTenantId(String id, String tenantId);

    /** Row lock so confirm, the poller and an admin resolve serialise on one booking. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM PlatformBooking b WHERE b.id = :id")
    Optional<PlatformBooking> findByIdForUpdate(@Param("id") String id);

    Optional<PlatformBooking> findBySupplierReviewId(String supplierReviewId);

    Optional<PlatformBooking> findByIdempotencyKey(String idempotencyKey);

    List<PlatformBooking> findByStatusIn(Collection<PlatformBookingStatus> statuses);

    List<PlatformBooking> findByTenantIdOrderByCreatedAtDesc(String tenantId, Pageable pageable);

    List<PlatformBooking> findAllByOrderByCreatedAtDesc(Pageable pageable);

    boolean existsByStatusIn(Collection<PlatformBookingStatus> statuses);

    long countByStatusIn(Collection<PlatformBookingStatus> statuses);
}
