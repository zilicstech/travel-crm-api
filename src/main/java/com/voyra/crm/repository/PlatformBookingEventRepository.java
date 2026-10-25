package com.voyra.crm.repository;

import com.voyra.crm.entity.PlatformBookingEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlatformBookingEventRepository extends JpaRepository<PlatformBookingEvent, String> {

    List<PlatformBookingEvent> findByPlatformBookingIdOrderByCreatedAtAsc(String platformBookingId);

    Optional<PlatformBookingEvent> findFirstByPlatformBookingIdAndTypeOrderByCreatedAtDesc(String platformBookingId, String type);
}
