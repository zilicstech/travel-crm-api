package com.voyra.crm.repository;

import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.enums.PlatformBookingStatus;
import com.voyra.crm.enums.SupplierEnvironment;
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

    long countByStatusInAndEnvironmentNot(Collection<PlatformBookingStatus> statuses, SupplierEnvironment environment);

    List<PlatformBooking> findByMirrorStatusIn(Collection<com.voyra.crm.enums.MirrorStatus> statuses);

    List<PlatformBooking> findByTenantIdOrderByCreatedAtDesc(String tenantId, Pageable pageable);

    Page<PlatformBooking> findByTenantId(String tenantId, Pageable pageable);

    Page<PlatformBooking> findByTenantIdAndBookedBy(String tenantId, String bookedBy, Pageable pageable);

    List<PlatformBooking> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * The agency summary: per product and status, rows created in [from, to) - count, displayed fare and what the agency
     * earns (commission less TDS; no commission counts 0). {@code bookedBy} null = the whole agency.
     */
    @Query("""
            SELECT b.product, b.status, COUNT(b), COALESCE(SUM(b.displayed), 0),
                   COALESCE(SUM(CASE WHEN b.agencyCommission IS NULL THEN 0 ELSE b.agencyCommission - COALESCE(b.tds, 0) END), 0)
            FROM PlatformBooking b
            WHERE b.tenantId = :tenantId AND (:bookedBy IS NULL OR b.bookedBy = :bookedBy)
              AND b.createdAt >= :from AND b.createdAt < :to AND b.status NOT IN :excluded
            GROUP BY b.product, b.status
            """)
    List<Object[]> summarize(@Param("tenantId") String tenantId, @Param("bookedBy") String bookedBy, @Param("from") Instant from,
                             @Param("to") Instant to, @Param("excluded") Collection<PlatformBookingStatus> excluded);

    /**
     * Ticketed rows travelling on or after {@code today} (YYYY-MM-DD), soonest first. The travel date is the one stamped
     * in the pricing snapshot at review (flight: first departure, hotel: check-in), else a hotel's itinerary check-in.
     */
    // shortcut: a flight reviewed before travelDate was stamped (slice 6) has no date here and is left out; parse sI[].dt if those matter.
    @Query(value = """
            SELECT * FROM platform_booking b
            WHERE b.tenant_id = :tenantId AND (CAST(:bookedBy AS varchar) IS NULL OR b.booked_by = CAST(:bookedBy AS varchar))
              AND b.status = 'TICKETED'
              AND COALESCE(b.pricing_snapshot ->> 'travelDate', b.itinerary -> 'stay' ->> 'checkIn') >= :today
            ORDER BY COALESCE(b.pricing_snapshot ->> 'travelDate', b.itinerary -> 'stay' ->> 'checkIn'), b.created_at
            LIMIT 5
            """, nativeQuery = true)
    List<PlatformBooking> findUpcoming(@Param("tenantId") String tenantId, @Param("bookedBy") String bookedBy, @Param("today") String today);

    @Query("""
            SELECT b FROM PlatformBooking b
            WHERE b.tenantId = :tenantId AND (:bookedBy IS NULL OR b.bookedBy = :bookedBy) AND b.status IN :statuses
            ORDER BY b.createdAt DESC
            """)
    List<PlatformBooking> findNewestInStatuses(@Param("tenantId") String tenantId, @Param("bookedBy") String bookedBy,
                                               @Param("statuses") Collection<PlatformBookingStatus> statuses, Pageable pageable);

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
