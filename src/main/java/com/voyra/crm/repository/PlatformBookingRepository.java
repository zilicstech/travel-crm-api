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

    /** Ids only, so the statement import never holds an unlocked copy of a row it will lock and change. */
    @Query("SELECT b.id FROM PlatformBooking b WHERE b.supplierBookingId = :supplierBookingId AND b.environment = :environment")
    List<String> findIdsBySupplierBookingId(@Param("supplierBookingId") String supplierBookingId,
                                            @Param("environment") com.voyra.crm.enums.SupplierEnvironment environment);

    @Query("SELECT b.id FROM PlatformBooking b WHERE b.pnr = :pnr AND b.environment = :environment")
    List<String> findIdsByPnr(@Param("pnr") String pnr, @Param("environment") com.voyra.crm.enums.SupplierEnvironment environment);

    /** The P&L window: bookings ticketed in [from, to). */
    List<PlatformBooking> findByTicketedAtGreaterThanEqualAndTicketedAtLessThan(Instant from, Instant to);

    Optional<PlatformBooking> findByIdempotencyKey(String idempotencyKey);

    List<PlatformBooking> findByStatusIn(Collection<PlatformBookingStatus> statuses);

    List<PlatformBooking> findByMirrorStatusIn(Collection<com.voyra.crm.enums.MirrorStatus> statuses);

    List<PlatformBooking> findByTenantIdOrderByCreatedAtDesc(String tenantId, Pageable pageable);

    Page<PlatformBooking> findByTenantId(String tenantId, Pageable pageable);

    Page<PlatformBooking> findByTenantIdAndBookedBy(String tenantId, String bookedBy, Pageable pageable);

    List<PlatformBooking> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Ids of finished, not yet purged bookings created before {@code createdBefore} - the purge re-checks each one under its lock. */
    @Query("SELECT b.id FROM PlatformBooking b WHERE b.paxPurgedAt IS NULL AND b.status IN :statuses AND b.createdAt < :createdBefore ORDER BY b.createdAt")
    List<String> findPurgeCandidateIds(@Param("statuses") Collection<PlatformBookingStatus> statuses, @Param("createdBefore") Instant createdBefore);

    boolean existsByStatusIn(Collection<PlatformBookingStatus> statuses);

    long countByStatusIn(Collection<PlatformBookingStatus> statuses);

    /** Admin-resolved FAILED rows still inside the late-ticket window. */
    List<PlatformBooking> findByStatusAndResolvedFailedAtAfter(PlatformBookingStatus status, Instant after);

    /**
     * REVIEWING rows past their review expiry (or created before {@code staleBefore} when it has none): the sweep aborts
     * them, releasing a hold left behind by a confirm transaction that rolled back if there is one.
     */
    @Query("""
            SELECT b FROM PlatformBooking b
            WHERE b.status = com.voyra.crm.enums.PlatformBookingStatus.REVIEWING
              AND ((b.reviewExpiresAt IS NOT NULL AND b.reviewExpiresAt < :now)
                   OR (b.reviewExpiresAt IS NULL AND b.createdAt < :staleBefore))
            """)
    List<PlatformBooking> findExpiredReviews(@Param("now") Instant now, @Param("staleBefore") Instant staleBefore);
}
