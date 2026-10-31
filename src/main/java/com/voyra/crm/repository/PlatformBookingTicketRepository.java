package com.voyra.crm.repository;

import com.voyra.crm.entity.PlatformBookingTicket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PlatformBookingTicketRepository extends JpaRepository<PlatformBookingTicket, String> {

    /** 0 when the booking has no download yet; read under the booking row lock. */
    @Query("SELECT COALESCE(MAX(t.seq), 0) FROM PlatformBookingTicket t WHERE t.platformBookingId = :bookingId")
    int maxSeq(@Param("bookingId") String bookingId);

    List<PlatformBookingTicket> findByPlatformBookingIdAndTenantIdOrderBySeqDesc(String platformBookingId, String tenantId);

    /** A ticket id under another booking or agency is simply absent (404). */
    Optional<PlatformBookingTicket> findByIdAndPlatformBookingIdAndTenantId(String id, String platformBookingId, String tenantId);
}
