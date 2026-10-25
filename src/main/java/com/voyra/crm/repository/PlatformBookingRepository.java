package com.voyra.crm.repository;

import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.enums.PlatformBookingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PlatformBookingRepository extends JpaRepository<PlatformBooking, String>, JpaSpecificationExecutor<PlatformBooking> {

    /** Tenant-scoped lookup: another agency's id is simply absent (404). */
    Optional<PlatformBooking> findByIdAndTenantId(String id, String tenantId);

    /** Row lock so confirm, the poller and an admin resolve serialise on one booking. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM PlatformBooking b WHERE b.id = :id")
    Optional<PlatformBooking> findByIdForUpdate(@Param("id") String id);

    Optional<PlatformBooking> findBySupplierReviewId(String supplierReviewId);

    Optional<PlatformBooking> findByIdempotencyKey(String idempotencyKey);

    List<PlatformBooking> findByStatusIn(Collection<PlatformBookingStatus> statuses);

    List<PlatformBooking> findByMirrorStatusIn(Collection<com.voyra.crm.enums.MirrorStatus> statuses);

    List<PlatformBooking> findByTenantIdOrderByCreatedAtDesc(String tenantId, Pageable pageable);

    Page<PlatformBooking> findByTenantId(String tenantId, Pageable pageable);

    Page<PlatformBooking> findByTenantIdAndBookedBy(String tenantId, String bookedBy, Pageable pageable);

    List<PlatformBooking> findAllByOrderByCreatedAtDesc(Pageable pageable);

    boolean existsByStatusIn(Collection<PlatformBookingStatus> statuses);

    long countByStatusIn(Collection<PlatformBookingStatus> statuses);

    /** Admin-resolved FAILED rows still inside the late-ticket window. */
    List<PlatformBooking> findByStatusAndResolvedFailedAtAfter(PlatformBookingStatus status, Instant after);

    /**
     * REVIEWING rows past their review expiry (or created before {@code staleBefore} when it has none) that still carry
     * an open {@code pb-hold:<id>} - a hold whose confirm transaction rolled back. Bounded by the open-hold check.
     */
    @Query("""
            SELECT b FROM PlatformBooking b
            WHERE b.status = com.voyra.crm.enums.PlatformBookingStatus.REVIEWING
              AND ((b.reviewExpiresAt IS NOT NULL AND b.reviewExpiresAt < :now)
                   OR (b.reviewExpiresAt IS NULL AND b.createdAt < :staleBefore))
              AND EXISTS (SELECT 1 FROM WalletEntry h
                          WHERE h.idempotencyKey = CONCAT('pb-hold:', b.id)
                            AND NOT EXISTS (SELECT 1 FROM WalletEntry r
                                            WHERE r.type = com.voyra.crm.enums.WalletEntryType.HOLD_RELEASE
                                              AND r.relatedEntryId = h.id))
            """)
    List<PlatformBooking> findExpiredReviewsWithOpenHold(@Param("now") Instant now, @Param("staleBefore") Instant staleBefore);
}
