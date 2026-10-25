package com.voyra.crm.repository;

import com.voyra.crm.entity.PlatformBookingEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PlatformBookingEventRepository extends JpaRepository<PlatformBookingEvent, String> {

    List<PlatformBookingEvent> findByPlatformBookingIdOrderByCreatedAtAsc(String platformBookingId);
}
