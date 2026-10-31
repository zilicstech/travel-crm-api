package com.voyra.crm.repository;

import com.voyra.crm.entity.PlatformBookingEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PlatformBookingEventRepository extends JpaRepository<PlatformBookingEvent, String> {

    List<PlatformBookingEvent> findByPlatformBookingIdOrderByCreatedAtAsc(String platformBookingId);

    List<PlatformBookingEvent> findByPlatformBookingIdInAndType(java.util.Collection<String> platformBookingIds, String type);

    long countByPlatformBookingIdAndType(String platformBookingId, String type);

    long countByPlatformBookingIdAndTypeAndCreatedAtAfter(String platformBookingId, String type, Instant after);

    Optional<PlatformBookingEvent> findFirstByPlatformBookingIdAndTypeOrderByCreatedAtDesc(String platformBookingId, String type);
}
