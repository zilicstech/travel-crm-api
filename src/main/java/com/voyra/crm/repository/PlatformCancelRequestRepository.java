package com.voyra.crm.repository;

import com.voyra.crm.entity.PlatformCancelRequest;
import com.voyra.crm.enums.CancelRequestStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PlatformCancelRequestRepository extends JpaRepository<PlatformCancelRequest, String> {

    /** Row lock, taken after the booking's own lock, so every transition of one request serialises. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM PlatformCancelRequest r WHERE r.id = :id")
    Optional<PlatformCancelRequest> findByIdForUpdate(@Param("id") String id);

    List<PlatformCancelRequest> findByPlatformBookingIdOrderByCreatedAtAsc(String platformBookingId);

    List<PlatformCancelRequest> findByPlatformBookingIdInOrderByCreatedAtAsc(Collection<String> bookingIds);

    List<PlatformCancelRequest> findByPlatformBookingIdInAndStatusIn(Collection<String> bookingIds, Collection<CancelRequestStatus> statuses);

    boolean existsByPlatformBookingIdAndStatusIn(String platformBookingId, Collection<CancelRequestStatus> statuses);

    Page<PlatformCancelRequest> findByStatusIn(Collection<CancelRequestStatus> statuses, Pageable pageable);

    List<PlatformCancelRequest> findByStatusIn(Collection<CancelRequestStatus> statuses);

    long countByStatusIn(Collection<CancelRequestStatus> statuses);

    /** Finished requests whose personal data (remarks, note, proof files) is due to be cleared. */
    List<PlatformCancelRequest> findByFinishedAtBeforeAndPersonalDataPurgedAtIsNull(Instant before);

    /**
     * The summary's partial cancellations: per product, refunds and recalls of completed requests on bookings still
     * ticketed, created in [from, to) ({@code bookedBy} null = the whole agency). A whole cancellation is CANCELLED and
     * already out of the ticketed sales.
     */
    @Query("""
            SELECT b.product, COALESCE(SUM(r.refund), 0), COALESCE(SUM(r.recall), 0)
            FROM PlatformCancelRequest r, PlatformBooking b
            WHERE r.platformBookingId = b.id AND r.status = com.voyra.crm.enums.CancelRequestStatus.COMPLETED
              AND b.status = com.voyra.crm.enums.PlatformBookingStatus.TICKETED
              AND b.tenantId = :tenantId AND (:bookedBy IS NULL OR b.bookedBy = :bookedBy)
              AND b.createdAt >= :from AND b.createdAt < :to
            GROUP BY b.product
            """)
    List<Object[]> partialCancellations(@Param("tenantId") String tenantId, @Param("bookedBy") String bookedBy,
                                        @Param("from") Instant from, @Param("to") Instant to);
}
