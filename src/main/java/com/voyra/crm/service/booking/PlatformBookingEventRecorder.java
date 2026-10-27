package com.voyra.crm.service.booking;

import com.voyra.crm.entity.PlatformBookingEvent;
import com.voyra.crm.repository.PlatformBookingEventRepository;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

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

    /** Payload of the latest event of {@code type} on the booking (as stored, i.e. redacted). */
    public Optional<Map<String, Object>> lastPayload(String bookingId, String type) {
        return repository.findFirstByPlatformBookingIdAndTypeOrderByCreatedAtDesc(bookingId, type).map(PlatformBookingEvent::getPayload);
    }
}
