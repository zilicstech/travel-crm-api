package com.voyra.crm.service.booking;

import com.voyra.crm.entity.PlatformBookingEvent;
import com.voyra.crm.repository.PlatformBookingEventRepository;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Appends a platform_booking_event in the caller's transaction, so an event commits with the transition it records. */
@Component
@RequiredArgsConstructor
public class PlatformBookingEventRecorder {

    private final PlatformBookingEventRepository repository;
    private final PaxCodec paxCodec;

    public void record(String bookingId, String type, Object payload) {
        repository.save(PlatformBookingEvent.builder()
                .id(UniqueIdResolver.resolve(repository::existsById))
                .platformBookingId(bookingId)
                .type(type)
                .payload(payload == null ? null : paxCodec.redact(payload))
                .createdAt(Instant.now())
                .build());
    }
}
