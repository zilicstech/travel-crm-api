package com.voyra.crm.service.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.entity.PlatformBookingEvent;
import com.voyra.crm.enums.MirrorStatus;
import com.voyra.crm.enums.PlatformBookingStatus;
import com.voyra.crm.service.booking.PlatformBookingPurgeTxService.PurgeOutcome;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.repository.PlatformBookingEventRepository;
import com.voyra.crm.repository.PlatformBookingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PlatformBookingPurgeTxServiceTest {

    private static final int DAYS = 90;
    private static final Instant NOW = Instant.parse("2026-12-01T00:00:00Z");

    @Mock private PlatformBookingRepository bookings;
    @Mock private PlatformBookingEventRepository events;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private PlatformBookingPurgeTxService service() {
        PaxCodec codec = new PaxCodec(objectMapper, "dGVzdC1vbmx5LWFlcy1rZXktMzItYnl0ZXMtbG9uZyE=");
        PlatformBookingQueryService query = new PlatformBookingQueryService(bookings, events, null, null, null, codec, objectMapper);
        return new PlatformBookingPurgeTxService(bookings, events, query);
    }

    private static Instant daysAgo(long d) {
        return NOW.minus(Duration.ofDays(d));
    }

    private static Map<String, Object> flightOn(LocalDate departure) {
        return Map.of("tripInfos", List.of(Map.of("sI", List.of(Map.of("dt", departure + "T09:00", "da", Map.of("code", "DEL"))))));
    }

    private PlatformBooking booking(PlatformBookingStatus status, Instant created) {
        return PlatformBooking.builder().id("b1").tenantId("t1").product(PricingProduct.FLIGHT).status(status)
                .environment(SupplierEnvironment.UAT).paxEnc("CIPHER").confirmPayloadHash("hash").supplierMessage("Mr Rahul Sharma refused")
                .displayed(new BigDecimal("1000.00")).pnr("ABC123").createdAt(created).itinerary(flightOn(created.atZone(ZoneOffset.UTC).toLocalDate().plusDays(5)))
                .build();
    }

    private void stub(PlatformBooking b, PlatformBookingEvent... evs) {
        given(bookings.findByIdForUpdate("b1")).willReturn(Optional.of(b));
        given(events.findByPlatformBookingIdOrderByCreatedAtAsc("b1")).willReturn(List.of(evs));
    }

    private static PlatformBookingEvent event(String type, Map<String, Object> payload, Instant at) {
        return PlatformBookingEvent.builder().id("e-" + type).platformBookingId("b1").type(type).payload(payload).createdAt(at).build();
    }

    @ParameterizedTest
    @EnumSource(value = PlatformBookingStatus.class, names = {"REVIEWING", "HELD", "BOOKING", "UNKNOWN", "CANCELLING"})
    void anOpenBookingIsNeverPurgedHoweverOld(PlatformBookingStatus status) {
        PlatformBooking b = booking(status, daysAgo(5000));
        given(bookings.findByIdForUpdate("b1")).willReturn(Optional.of(b));

        assertThat(service().purge("b1", NOW, DAYS)).isNotEqualTo(PurgeOutcome.PURGED);

        assertThat(b.getPaxEnc()).isEqualTo("CIPHER");
        assertThat(b.getPaxPurgedAt()).isNull();
        verify(bookings, never()).save(any());
    }

    @Test
    void aTicketedBookingIsKeptUntilTravelPlusRetention() {
        PlatformBooking b = booking(PlatformBookingStatus.TICKETED, daysAgo(400));
        b.setItinerary(flightOn(LocalDate.of(2026, 12, 1).minusDays(DAYS - 2))); // travelled 88 days ago
        stub(b);

        assertThat(service().purge("b1", NOW, DAYS)).isNotEqualTo(PurgeOutcome.PURGED);
        assertThat(b.getPaxEnc()).isEqualTo("CIPHER");
    }

    @Test
    void aTicketedBookingIsPurgedOnceTravelPlusRetentionHasPassed() {
        PlatformBooking b = booking(PlatformBookingStatus.TICKETED, daysAgo(400));
        b.setItinerary(flightOn(LocalDate.of(2026, 12, 1).minusDays(DAYS + 2)));
        stub(b);

        assertThat(service().purge("b1", NOW, DAYS)).isEqualTo(PurgeOutcome.PURGED);
        assertThat(b.getPaxEnc()).isNull();
        assertThat(b.getConfirmPayloadHash()).isNull();
        assertThat(b.getSupplierMessage()).isNull();
        assertThat(b.getPaxPurgedAt()).isEqualTo(NOW);
        assertThat(b.getPnr()).isEqualTo("ABC123");
        assertThat(b.getDisplayed()).isEqualByComparingTo("1000.00");
    }

    @Test
    void aFutureTravelTicketedBookingIsKeptEvenIfTicketedLongAgo() {
        PlatformBooking b = booking(PlatformBookingStatus.TICKETED, daysAgo(300));
        b.setItinerary(flightOn(LocalDate.of(2027, 3, 1)));
        stub(b);

        assertThat(service().purge("b1", NOW, DAYS)).isNotEqualTo(PurgeOutcome.PURGED);
    }

    @Test
    void aHotelUsesTheCheckOutDate() {
        PlatformBooking b = booking(PlatformBookingStatus.TICKETED, daysAgo(400));
        b.setProduct(PricingProduct.HOTEL);
        b.setItinerary(Map.of("hotel", Map.of("name", "H"), "stay", Map.of("checkIn", "2026-08-01", "checkOut", "2026-08-03")));
        stub(b);

        assertThat(service().purge("b1", NOW, DAYS)).isEqualTo(PurgeOutcome.PURGED); // 2026-08-03 + 90 days = 2026-11-01, past

        PlatformBooking recent = booking(PlatformBookingStatus.TICKETED, daysAgo(400));
        recent.setProduct(PricingProduct.HOTEL);
        recent.setItinerary(Map.of("stay", Map.of("checkIn", "2026-10-01", "checkOut", "2026-10-03")));
        stub(recent);
        assertThat(service().purge("b1", NOW, DAYS)).isNotEqualTo(PurgeOutcome.PURGED);
    }

    @ParameterizedTest
    @EnumSource(value = PlatformBookingStatus.class, names = {"CANCELLED", "FAILED", "ABORTED"})
    void aTerminalBookingIsPurgedAfterRetentionAndNotBefore(PlatformBookingStatus status) {
        PlatformBooking old = booking(status, daysAgo(200));
        stub(old, event("X", Map.of("by", "u"), daysAgo(95)));
        assertThat(service().purge("b1", NOW, DAYS)).isEqualTo(PurgeOutcome.PURGED);
        assertThat(old.getPaxEnc()).isNull();

        PlatformBooking fresh = booking(status, daysAgo(200));
        stub(fresh, event("X", Map.of("by", "u"), daysAgo(10)));
        assertThat(service().purge("b1", NOW, DAYS)).isNotEqualTo(PurgeOutcome.PURGED);
        assertThat(fresh.getPaxEnc()).isEqualTo("CIPHER");
    }

    @Test
    void aSecondRunDoesNothing() {
        PlatformBooking b = booking(PlatformBookingStatus.CANCELLED, daysAgo(200));
        b.setPaxEnc(null);
        b.setPaxPurgedAt(daysAgo(1));
        given(bookings.findByIdForUpdate("b1")).willReturn(Optional.of(b));

        assertThat(service().purge("b1", NOW, DAYS)).isNotEqualTo(PurgeOutcome.PURGED);
        verify(bookings, never()).save(any());
    }

    @Test
    void personalFieldsInEventsAreRedactedAndMoneyKept() {
        PlatformBooking b = booking(PlatformBookingStatus.FAILED, daysAgo(200));
        PlatformBookingEvent failed = event("FAILED", Map.of("message", "Name mismatch for Rahul Sharma"), daysAgo(150));
        PlatformBookingEvent held = event("HELD", Map.of("amount", new BigDecimal("1000.00"), "holdEntryId", "w1"), daysAgo(160));
        PlatformBookingEvent resolved = event("RESOLVED", Map.of("note", "call with Rahul", "by", "u1"), daysAgo(149));
        stub(b, held, failed, resolved);

        assertThat(service().purge("b1", NOW, DAYS)).isEqualTo(PurgeOutcome.PURGED);

        assertThat(failed.getPayload()).containsEntry("message", "[removed]");
        assertThat(resolved.getPayload()).containsEntry("note", "[removed]").containsEntry("by", "u1");
        assertThat(held.getPayload()).containsEntry("holdEntryId", "w1").containsEntry("amount", new BigDecimal("1000.00"));
        verify(events, never()).save(held);
    }

    @Test
    void aBookingThatMovedOnUnderTheLockIsSkipped() {
        // the job's scan saw TICKETED; by the time the lock is held the agency started a cancel
        PlatformBooking b = booking(PlatformBookingStatus.CANCELLING, daysAgo(400));
        b.setItinerary(flightOn(LocalDate.of(2026, 1, 1)));
        given(bookings.findByIdForUpdate("b1")).willReturn(Optional.of(b));

        assertThat(service().purge("b1", NOW, DAYS)).isNotEqualTo(PurgeOutcome.PURGED);
        assertThat(b.getPaxEnc()).isEqualTo("CIPHER");
    }

    /**
     * Carried minor: a booking whose CRM mirror is still PENDING or FAILED keeps its passengers - the repair job builds the
     * agency's booking from them - and is counted as skipped. A booking with no client has no mirror and purges.
     */
    @ParameterizedTest
    @EnumSource(value = MirrorStatus.class, names = {"PENDING", "FAILED"})
    void aBookingWaitingForItsCrmMirrorIsNotPurged(MirrorStatus mirror) {
        PlatformBooking b = booking(PlatformBookingStatus.FAILED, daysAgo(200));
        b.setClientId("c1");
        b.setMirrorStatus(mirror);
        stub(b);

        assertThat(service().purge("b1", NOW, DAYS)).isEqualTo(PurgeOutcome.SKIPPED_MIRROR);
        assertThat(b.getPaxEnc()).isEqualTo("CIPHER");
        verify(bookings, never()).save(any());

        b.setClientId(null); // no client: nothing to mirror
        assertThat(service().purge("b1", NOW, DAYS)).isEqualTo(PurgeOutcome.PURGED);
    }

    /** Carried minor: these exact keys (any case, underscores ignored) are personal; a key merely containing one is not. */
    @Test
    void passportNationalityAndContactKeysAreRedactedExactly() {
        Map<String, Object> redacted = PlatformBookingPurgeTxService.redact(Map.of("passport", "N1234567", "PassportNo", "N1234567",
                "nationality", "IN", "contactEmail", "a@b.test", "contact_phone", "98765", "passportExpiryWarning", "soon", "phoneCount", 2));

        assertThat(redacted).containsEntry("passport", "[removed]").containsEntry("PassportNo", "[removed]")
                .containsEntry("nationality", "[removed]").containsEntry("contactEmail", "[removed]").containsEntry("contact_phone", "[removed]")
                .containsEntry("passportExpiryWarning", "soon").containsEntry("phoneCount", 2);
    }
}
