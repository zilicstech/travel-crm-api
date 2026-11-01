package com.voyra.crm.service.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.context.TenantContext;
import com.voyra.crm.dto.ItinerarySegment;
import com.voyra.crm.dto.PaxInput;
import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.entity.PlatformBookingEvent;
import com.voyra.crm.enums.MirrorStatus;
import com.voyra.crm.enums.PlatformBookingStatus;
import com.voyra.crm.repository.PlatformBookingEventRepository;
import com.voyra.crm.repository.PlatformBookingRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.security.SystemActor;
import com.voyra.crm.service.NotificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The CRM mirror runs after the platform money committed: it is created only for a booking naming a CRM client, a
 * failure marks FAILED and alerts without touching money, and the tenant hop always restores the caller's context.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlatformBookingMirrorServiceTest {

    private static final String KEY = "dGVzdC1vbmx5LWFlcy1rZXktMzItYnl0ZXMtbG9uZyE=";

    @Mock private PlatformBookingRepository repository;
    @Mock private PlatformBookingEventRepository eventRepository;
    @Mock private PlatformBookingMirrorTenantService tenantService;
    @Mock private PlatformBookingQueryService queryService;
    @Mock private NotificationService notificationService;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private com.voyra.crm.service.EntitlementService entitlementService;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final PaxCodec paxCodec = new PaxCodec(objectMapper, KEY);
    private final List<PlatformBookingEvent> recorded = new ArrayList<>();
    private PlatformBookingMirrorService service;

    @BeforeEach
    void setUp() {
        service = new PlatformBookingMirrorService(repository, tenantService, queryService, paxCodec,
                new PlatformBookingEventRecorder(eventRepository, paxCodec), notificationService, entitlementService, transactionManager);
        crmCore(true);
        when(eventRepository.save(any())).thenAnswer(i -> {
            recorded.add(i.getArgument(0));
            return i.getArgument(0);
        });
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(queryService.itinerary(any())).thenReturn(List.of(
                new ItinerarySegment("DEL", "BOM", "2026-12-02T09:00", "2026-12-02T11:10", "IndiGo", "6E-201")));
    }

    private void crmCore(boolean on) {
        when(entitlementService.resolve("T1")).thenReturn(new com.voyra.crm.models.Entitlements(com.voyra.crm.enums.SubscriptionPlan.SUITE,
                com.voyra.crm.enums.SubscriptionStatus.ACTIVE, on ? java.util.EnumSet.allOf(com.voyra.crm.enums.Sku.class) : java.util.Set.of(),
                5, false, null));
    }

    /** A Basic agency keeps no CRM booking: nothing is written and the row waits as NONE for a backfill. */
    @Test
    void aBasicAgencyGetsNoCrmBooking() {
        PlatformBooking b = ticketed("C1");
        crmCore(false);

        service.sync("PB1");

        verifyNoInteractions(tenantService);
        assertThat(b.getMirrorStatus()).isEqualTo(MirrorStatus.NONE);
    }

    /** An agency that dropped CRM Core still keeps the CRM booking it already has in line. */
    @Test
    void aCrmBookingMadeBeforeADowngradeStaysInLine() {
        PlatformBooking b = ticketed("C1");
        b.setTenantBookingId("B1");
        crmCore(false);
        when(tenantService.ensure(any(), anyList(), any(), anyList())).thenReturn("B1");

        service.sync("PB1");

        verify(tenantService).ticket(eq("B1"), eq(b), anyList(), anyList());
        assertThat(b.getMirrorStatus()).isEqualTo(MirrorStatus.DONE);
    }

    /** Once the agency has CRM Core, an earlier booking is queued and synced, showing it as it is now. */
    @Test
    void backfillQueuesAndSyncsAnEarlierBooking() {
        PlatformBooking b = ticketed("C1");
        b.setMirrorStatus(MirrorStatus.NONE);
        when(tenantService.ensure(any(), anyList(), any(), anyList())).thenReturn("B1");

        service.backfill("PB1");

        assertThat(recorded).extracting(PlatformBookingEvent::getType).startsWith("MIRROR_QUEUED");
        verify(tenantService).ticket(eq("B1"), eq(b), anyList(), anyList());
        assertThat(b.getMirrorStatus()).isEqualTo(MirrorStatus.DONE);
        assertThat(b.getTenantBookingId()).isEqualTo("B1");
    }

    /** Only a row still NONE, with a client, at a status the CRM shows. */
    @Test
    void backfillLeavesOtherRowsAlone() {
        PlatformBooking b = ticketed("C1");
        b.setMirrorStatus(MirrorStatus.FAILED);
        service.backfill("PB1");
        b.setMirrorStatus(MirrorStatus.NONE);
        b.setClientId(null);
        service.backfill("PB1");
        b.setClientId("C1");
        b.setStatus(PlatformBookingStatus.HELD);
        service.backfill("PB1");

        verifyNoInteractions(tenantService);
        assertThat(recorded).isEmpty();
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private PlatformBooking ticketed(String clientId) {
        PaxInput pax = new PaxInput();
        pax.setType("ADULT");
        pax.setFirstName("Rahul");
        pax.setLastName("Sharma");
        PlatformBooking b = PlatformBooking.builder().id("PB1").tenantId("T1").clientId(clientId)
                .status(PlatformBookingStatus.TICKETED).ticketedAt(java.time.Instant.parse("2026-11-01T10:00:00Z")).pnr("ABC123").ticketNumbers(List.of("0981234567890"))
                .displayed(new BigDecimal("5400.00")).gross(new BigDecimal("5500.00")).base(new BigDecimal("4500.00"))
                .debitEntryId("WE-DEBIT").mirrorStatus(clientId == null ? MirrorStatus.NONE : MirrorStatus.PENDING)
                .paxEnc(paxCodec.encrypt(Map.of("passengers", List.of(pax), "contactEmail", "a@b.c", "contactPhone", "9")))
                .build();
        when(repository.findById("PB1")).thenReturn(Optional.of(b));
        when(repository.findByIdForUpdate("PB1")).thenReturn(Optional.of(b));
        return b;
    }

    /** An owner booking for a client (no lead, no agents in the agency) still mirrors: the owner is its agent. */
    @Test
    void ownerBookingMirrorsUnderOwner() {
        PlatformBooking crm = ticketed("C1");
        crm.setBookedBy(crm.getTenantId());
        crm.setLeadId(null);
        crm.setServiceId(null);
        when(tenantService.ensure(any(), anyList(), any(), anyList())).thenReturn("B1");

        service.sync("PB1");

        verify(tenantService).ensure(org.mockito.ArgumentMatchers.argThat(p -> "C1".equals(p.getClientId())
                && p.getLeadId() == null && p.getBookedBy().equals(p.getTenantId())), anyList(), any(), anyList());
        assertThat(crm.getMirrorStatus()).isEqualTo(MirrorStatus.DONE);
    }

    @Test
    void mirrorCreatedOnlyWithAClient() {
        PlatformBooking starter = ticketed(null);

        service.sync("PB1");

        verifyNoInteractions(tenantService);
        assertThat(starter.getMirrorStatus()).isEqualTo(MirrorStatus.NONE);

        PlatformBooking crm = ticketed("C1");
        when(tenantService.ensure(any(), anyList(), any(), anyList())).thenReturn("B1");

        service.sync("PB1");

        ArgumentCaptor<List<PaxInput>> pax = ArgumentCaptor.forClass(List.class);
        verify(tenantService).ensure(eq(crm), anyList(), any(), pax.capture());
        assertThat(pax.getValue()).singleElement().satisfies(p -> assertThat(p.getLastName()).isEqualTo("Sharma"));
        verify(tenantService, never()).cancel(anyString(), any());
        assertThat(crm.getMirrorStatus()).isEqualTo(MirrorStatus.DONE);
        assertThat(crm.getTenantBookingId()).isEqualTo("B1");
        assertThat(recorded).extracting(PlatformBookingEvent::getType).contains("MIRRORED");
    }

    @Test
    void mirrorFailureLeavesMoneyAndMarksFailed() {
        PlatformBooking b = ticketed("C1");
        when(tenantService.ensure(any(), anyList(), any(), anyList()))
                .thenThrow(new IllegalStateException("Ledger account 1310 is not the Voyra Wallet"));

        service.sync("PB1"); // never throws: the ticket and its debit already committed

        assertThat(b.getMirrorStatus()).isEqualTo(MirrorStatus.FAILED);
        assertThat(b.getStatus()).isEqualTo(PlatformBookingStatus.TICKETED);
        assertThat(b.getDisplayed()).isEqualByComparingTo("5400.00");
        assertThat(b.getDebitEntryId()).isEqualTo("WE-DEBIT");
        assertThat(b.getTenantBookingId()).isNull();
        assertThat(recorded).extracting(PlatformBookingEvent::getType).contains("MIRROR_FAILED");
        verify(notificationService).platformAlert(org.mockito.ArgumentMatchers.contains("PB1"), anyString());
        assertThat(TenantContext.getTenantId()).isNull();
        assertThat(SystemActor.isActive()).isFalse();

        // the repair job's retry fails again: still FAILED, no second alert
        service.sync("PB1");
        verify(notificationService).platformAlert(anyString(), anyString());
    }

    /** A cancel that commits while create runs: the mirror is not marked DONE for a status it never saw, so a later sync cancels it. */
    @Test
    void aCancelLandingWhileCreateRunsLeavesTheMirrorPendingAndALaterSyncCancelsIt() {
        PlatformBooking seen = ticketed("C1");
        PlatformBooking live = ticketed("C1"); // the row as it is after the concurrent cancel committed
        when(repository.findById("PB1")).thenReturn(Optional.of(seen));
        when(repository.findByIdForUpdate("PB1")).thenReturn(Optional.of(live));
        when(tenantService.ensure(any(), anyList(), any(), anyList())).thenAnswer(i -> {
            live.setStatus(PlatformBookingStatus.CANCELLED);
            live.setCancelRefund(new BigDecimal("4000.00"));
            return "B1";
        });

        service.sync("PB1");

        verify(tenantService, never()).cancel(anyString(), any());
        assertThat(live.getMirrorStatus()).isEqualTo(MirrorStatus.PENDING);
        assertThat(live.getTenantBookingId()).isEqualTo("B1");

        // the repair job (or the cancel's own sync) now sees CANCELLED and cancels the CRM booking
        when(repository.findById("PB1")).thenReturn(Optional.of(live));
        service.sync("PB1");

        verify(tenantService).cancel("B1", live);
        assertThat(live.getMirrorStatus()).isEqualTo(MirrorStatus.DONE);
        assertThat(recorded).extracting(PlatformBookingEvent::getType).contains("MIRROR_CANCELLED");
    }

    @Test
    void aMirrorAlreadyDoneIsLeftAlone() {
        PlatformBooking b = ticketed("C1");
        b.setMirrorStatus(MirrorStatus.DONE);

        service.sync("PB1");

        verifyNoInteractions(tenantService);
    }

    /** The cancel hook runs on a scheduler or super-admin thread: no agency principal, another (or no) tenant. */
    @Test
    void mirrorCancelRunsAsTheSystemInTheAgencyTenantAndRestoresTheCallersContext() {
        PlatformBooking b = ticketed("C1");
        b.setStatus(PlatformBookingStatus.CANCELLED);
        b.setCancelRefund(new BigDecimal("4000.00"));
        b.setTenantBookingId("B1");
        TenantContext.setTenantId("T-OTHER");
        assertThat(SecurityContextUtil.getCurrentUser()).isEmpty();
        List<String> seen = new ArrayList<>();
        when(tenantService.ensure(any(), anyList(), any(), anyList())).thenAnswer(i -> {
            seen.add("ensure@" + TenantContext.getTenantId());
            return "B1";
        });
        doAnswer(i -> {
            CustomUserPrincipal actor = SecurityContextUtil.getCurrentUserOrThrow();
            seen.add("cancel@" + TenantContext.getTenantId() + " by " + actor.userId() + "/" + actor.tenantId()
                    + " system=" + SystemActor.isActive());
            return null;
        }).when(tenantService).cancel(eq("B1"), eq(b));

        service.sync("PB1");

        assertThat(seen).containsExactly("ensure@T1", "cancel@T1 by system/T1 system=true");
        assertThat(b.getMirrorStatus()).isEqualTo(MirrorStatus.DONE);
        assertThat(TenantContext.getTenantId()).isEqualTo("T-OTHER");
        assertThat(SystemActor.isActive()).isFalse();
        assertThat(SecurityContextUtil.getCurrentUser()).isEmpty();
    }

    @Test
    void aMirrorCancelFailureIsRecordedAndTheContextStillRestored() {
        PlatformBooking b = ticketed("C1");
        b.setStatus(PlatformBookingStatus.CANCELLED);
        given(tenantService.ensure(any(), anyList(), any(), anyList())).willReturn("B1");
        doAnswer(i -> {
            throw new IllegalStateException("boom");
        }).when(tenantService).cancel(anyString(), any());

        service.sync("PB1");

        assertThat(b.getMirrorStatus()).isEqualTo(MirrorStatus.FAILED);
        assertThat(TenantContext.getTenantId()).isNull();
        assertThat(SystemActor.isActive()).isFalse();
    }

    /** Called by mistake inside a transaction (after the money committed): the mirror fails and is retried, the caller never sees it. */
    @Test
    void aSyncInsideATransactionFailsTheMirrorNotTheCaller() {
        PlatformBooking b = ticketed("C1");
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            org.assertj.core.api.Assertions.assertThatCode(() -> service.sync("PB1")).doesNotThrowAnyException();
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }

        assertThat(b.getMirrorStatus()).isEqualTo(MirrorStatus.FAILED);
        verifyNoInteractions(tenantService);
    }

    /** Set markup runs as the system in the agency's schema on the user's behalf, restores the caller, and a refusal reaches the caller. */
    @Test
    void setMarkupRunsAsTheSystemAndPassesRefusalsOn() {
        PlatformBooking b = ticketed("C1");
        List<String> seen = new ArrayList<>();
        doAnswer(i -> {
            seen.add(TenantContext.getTenantId());
            seen.add(String.valueOf(SystemActor.isActive()));
            seen.add(SystemActor.author().name());
            return null;
        }).when(tenantService).setMarkup(b, new BigDecimal("500.00"));

        service.setMarkup(b, new BigDecimal("500.00"), new SystemActor.Author("T1", "Asha Rao"));

        assertThat(seen).containsExactly("T1", "true", "Asha Rao");
        assertThat(TenantContext.getTenantId()).isNull();
        assertThat(SystemActor.isActive()).isFalse();

        org.mockito.Mockito.doThrow(new com.voyra.crm.exception.BookingConflictException("PRICE_BELOW_INVOICED", "below", Map.of()))
                .when(tenantService).setMarkup(b, BigDecimal.ONE);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.setMarkup(b, BigDecimal.ONE, new SystemActor.Author("T1", "Asha Rao")))
                .isInstanceOf(com.voyra.crm.exception.BookingConflictException.class);
        assertThat(SystemActor.isActive()).isFalse();
    }

    @Test
    void setMarkupNeverRunsInsideAPlatformTransaction() {
        PlatformBooking b = ticketed("C1");
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.setMarkup(b, BigDecimal.ONE, new SystemActor.Author("T1", "Asha Rao")))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verifyNoInteractions(tenantService);
    }

    /** A hotel booking for a client is mirrored from its stay (a HOTEL_VOUCHER), never from flight legs. */
    @Test
    void aHotelBookingIsMirroredFromItsStay() {
        PlatformBooking hotel = ticketed("C1");
        hotel.setProduct(com.voyra.crm.enums.PricingProduct.HOTEL);
        com.voyra.crm.dto.HotelStaySummary stay = new com.voyra.crm.dto.HotelStaySummary("Sea Breeze Resort", "Goa",
                java.time.LocalDate.of(2026, 12, 1), java.time.LocalDate.of(2026, 12, 3), 2, 1, "Breakfast included");
        when(queryService.hotel(hotel)).thenReturn(stay);
        when(tenantService.ensure(any(), anyList(), any(), anyList())).thenReturn("B1");

        service.sync("PB1");

        verify(tenantService).ensure(eq(hotel), eq(List.of()), eq(stay), anyList());
        verify(tenantService).ticket(eq("B1"), eq(hotel), eq(List.of()), anyList());
        verify(queryService, never()).itinerary(any());
        assertThat(hotel.getMirrorStatus()).isEqualTo(MirrorStatus.DONE);
        assertThat(hotel.getTenantBookingId()).isEqualTo("B1");
    }

    /** A cancelled hotel booking is mirrored, then its mirror cancelled through the same cancel as a flight. */
    @Test
    void aCancelledHotelBookingCancelsItsMirror() {
        PlatformBooking hotel = ticketed("C1");
        hotel.setProduct(com.voyra.crm.enums.PricingProduct.HOTEL);
        hotel.setStatus(PlatformBookingStatus.CANCELLED);
        when(queryService.hotel(hotel)).thenReturn(new com.voyra.crm.dto.HotelStaySummary("Sea Breeze Resort", "Goa",
                java.time.LocalDate.of(2026, 12, 1), java.time.LocalDate.of(2026, 12, 3), 2, 1, null));
        when(tenantService.ensure(any(), anyList(), any(), anyList())).thenReturn("B1");

        service.sync("PB1");

        verify(tenantService).cancel("B1", hotel);
        assertThat(hotel.getMirrorStatus()).isEqualTo(MirrorStatus.DONE);
    }

    // ---------------------------------------------------------------- the reconciler: ensure, ticket, fail, cancel in order

    private PlatformBooking at(PlatformBookingStatus status, boolean ticketed) {
        PlatformBooking b = ticketed("C1");
        b.setStatus(status);
        b.setTicketedAt(ticketed ? b.getTicketedAt() : null);
        when(tenantService.ensure(any(), anyList(), any(), anyList())).thenReturn("B1");
        return b;
    }

    @Test
    void syncAtBookingCreatesAPendingRowAndNothingElse() {
        for (PlatformBookingStatus status : List.of(PlatformBookingStatus.BOOKING, PlatformBookingStatus.UNKNOWN)) {
            org.mockito.Mockito.clearInvocations(tenantService);
            PlatformBooking b = at(status, false);

            service.sync("PB1");

            verify(tenantService).ensure(eq(b), anyList(), any(), anyList());
            verify(tenantService, never()).ticket(anyString(), any(), anyList(), anyList());
            verify(tenantService, never()).fail(anyString(), any());
            verify(tenantService, never()).cancel(anyString(), any());
            assertThat(b.getMirrorStatus()).isEqualTo(MirrorStatus.DONE);
            assertThat(b.getTenantBookingId()).isEqualTo("B1");
        }
    }

    @Test
    void syncAtTicketedEnsuresThenTicketsAndPaysOnce() {
        PlatformBooking b = at(PlatformBookingStatus.TICKETED, true);

        service.sync("PB1");

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(tenantService);
        order.verify(tenantService).ensure(eq(b), anyList(), any(), anyList());
        order.verify(tenantService).ticket(eq("B1"), eq(b), anyList(), anyList()); // the ticket phase is the one that pays
        verify(tenantService, org.mockito.Mockito.times(1)).ticket(anyString(), any(), anyList(), anyList());
        verify(tenantService, never()).fail(anyString(), any());
        verify(tenantService, never()).cancel(anyString(), any());
        assertThat(b.getMirrorStatus()).isEqualTo(MirrorStatus.DONE);
    }

    /** The CRM booking is cancelled at the FAILED move's time (its event), not when this sync runs. */
    @Test
    void syncAtFailedEnsuresThenFails() {
        PlatformBooking b = at(PlatformBookingStatus.FAILED, false);
        java.time.Instant failedAt = java.time.Instant.now().minus(java.time.Duration.ofDays(5));
        when(eventRepository.findFirstByPlatformBookingIdAndTypeOrderByCreatedAtDesc("PB1", "FAILED"))
                .thenReturn(Optional.of(PlatformBookingEvent.builder().type("FAILED").createdAt(failedAt).build()));

        service.sync("PB1");

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(tenantService);
        order.verify(tenantService).ensure(eq(b), anyList(), any(), anyList());
        order.verify(tenantService).fail("B1", failedAt);
        verify(tenantService, never()).cancel(anyString(), any());
        assertThat(b.getMirrorStatus()).isEqualTo(MirrorStatus.DONE);
    }

    @Test
    void syncAtFailedNeverPays() {
        at(PlatformBookingStatus.FAILED, false); // no FAILED event found: the guard falls back to now

        service.sync("PB1");

        verify(tenantService, never()).ticket(anyString(), any(), anyList(), anyList());
        verify(tenantService).fail(eq("B1"), org.mockito.ArgumentMatchers.argThat(at ->
                !at.isBefore(java.time.Instant.now().minusSeconds(60))));
    }

    /** Review Focus 2: the platform only cancels a ticketed row, so a mirror still PENDING is ticketed (paid) first, then cancelled. */
    @Test
    void aCancelledRowWhoseMirrorNeverTicketedIsTicketedThenCancelled() {
        PlatformBooking b = at(PlatformBookingStatus.CANCELLED, true);
        b.setCancelRefund(new BigDecimal("4000.00"));

        service.sync("PB1");

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(tenantService);
        order.verify(tenantService).ensure(eq(b), anyList(), any(), anyList());
        order.verify(tenantService).ticket(eq("B1"), eq(b), anyList(), anyList());
        order.verify(tenantService).cancel("B1", b);
        verify(tenantService, never()).fail(anyString(), any());
        assertThat(b.getMirrorStatus()).isEqualTo(MirrorStatus.DONE);
    }

    // ---------------------------------------------------------------- coverage: rows not ready, odd payloads, finish races

    @Test
    void aMissingRowOrOneStillMovingIsNotMirrored() {
        when(repository.findById("NONE")).thenReturn(Optional.empty());
        service.sync("NONE");
        for (PlatformBookingStatus status : List.of(PlatformBookingStatus.REVIEWING, PlatformBookingStatus.HELD,
                PlatformBookingStatus.CANCELLING, PlatformBookingStatus.ABORTED)) {
            ticketed("C1").setStatus(status);
            service.sync("PB1");
        }

        verifyNoInteractions(tenantService);
    }

    @Test
    void aBookingWithoutStoredPassengersIsMirroredWithNone() {
        PlatformBooking b = ticketed("C1");
        b.setPaxEnc(null);
        when(tenantService.ensure(any(), anyList(), any(), anyList())).thenReturn("B1");

        service.sync("PB1");

        verify(tenantService).ensure(eq(b), anyList(), any(), eq(List.of()));
        assertThat(b.getMirrorStatus()).isEqualTo(MirrorStatus.DONE);
        assertThat(b.getTenantBookingId()).isEqualTo("B1");
    }

    @Test
    void storedPassengersWithoutAListAreMirroredWithNone() {
        PlatformBooking b = ticketed("C1");
        b.setPaxEnc(paxCodec.encrypt(Map.of("contactEmail", "pax-a@example.test")));
        when(tenantService.ensure(any(), anyList(), any(), anyList())).thenReturn("B1");

        service.sync("PB1");

        verify(tenantService).ensure(eq(b), anyList(), any(), eq(List.of()));
    }

    @Test
    void aRowGoneBeforeTheOutcomeIsRecordedIsLeftAlone() {
        PlatformBooking b = ticketed("C1");
        when(repository.findByIdForUpdate("PB1")).thenReturn(Optional.empty());
        when(tenantService.ensure(any(), anyList(), any(), anyList())).thenThrow(new IllegalStateException("boom"));

        service.sync("PB1");

        verify(repository, never()).save(any());
        verify(notificationService, never()).platformAlert(anyString(), anyString());
    }

    @Test
    void aFailureAfterAConcurrentSyncFinishedTheMirrorIsNotRecorded() {
        PlatformBooking seen = ticketed("C1");
        PlatformBooking live = seen.toBuilder().mirrorStatus(MirrorStatus.DONE).tenantBookingId("B9").build();
        when(repository.findByIdForUpdate("PB1")).thenReturn(Optional.of(live));
        when(tenantService.ensure(any(), anyList(), any(), anyList())).thenThrow(new IllegalStateException("duplicate"));

        service.sync("PB1");

        assertThat(live.getMirrorStatus()).isEqualTo(MirrorStatus.DONE);
        verify(repository, never()).save(any());
        verify(notificationService, never()).platformAlert(anyString(), anyString());
    }

    @Test
    void aStatusChangeDuringCreateKeepsAnAlreadyKnownTenantBooking() {
        PlatformBooking seen = ticketed("C1");
        PlatformBooking live = seen.toBuilder().status(PlatformBookingStatus.CANCELLED).tenantBookingId("B-OLD").build();
        when(repository.findByIdForUpdate("PB1")).thenReturn(Optional.of(live));
        when(tenantService.ensure(any(), anyList(), any(), anyList())).thenReturn("B-NEW");

        service.sync("PB1");

        assertThat(live.getTenantBookingId()).isEqualTo("B-OLD");
        verify(repository, never()).save(any());
    }

    @Test
    void aLongOrMissingErrorMessageIsCutOrKeptInTheAlert() {
        ticketed("C1");
        when(tenantService.ensure(any(), anyList(), any(), anyList())).thenThrow(new IllegalStateException("x".repeat(400)));

        service.sync("PB1");

        org.mockito.ArgumentCaptor<String> body = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(notificationService).platformAlert(anyString(), body.capture());
        assertThat(body.getValue()).contains("IllegalStateException: " + "x".repeat(300) + ")").doesNotContain("x".repeat(301));
        assertThat(recorded).filteredOn(e -> e.getType().equals("MIRROR_FAILED")).singleElement()
                .satisfies(e -> assertThat((String) e.getPayload().get("error")).hasSize(300));

        PlatformBooking again = ticketed("C1");
        again.setMirrorStatus(MirrorStatus.PENDING);
        org.mockito.Mockito.doThrow(new IllegalStateException()).when(tenantService).ensure(any(), anyList(), any(), anyList());
        service.sync("PB1");
        verify(notificationService, org.mockito.Mockito.times(2)).platformAlert(anyString(), body.capture());
        assertThat(body.getValue()).contains("IllegalStateException: null)");
    }

    @Test
    void linksAreCheckedByTheTenantService() {
        service.requireLinks("C1", "L1", "S1");

        verify(tenantService).requireLinks("C1", "L1", "S1");
    }
}
