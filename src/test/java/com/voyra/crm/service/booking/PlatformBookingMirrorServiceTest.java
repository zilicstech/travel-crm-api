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

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final PaxCodec paxCodec = new PaxCodec(objectMapper, KEY);
    private final List<PlatformBookingEvent> recorded = new ArrayList<>();
    private PlatformBookingMirrorService service;

    @BeforeEach
    void setUp() {
        service = new PlatformBookingMirrorService(repository, tenantService, queryService, paxCodec,
                new PlatformBookingEventRecorder(eventRepository, paxCodec), notificationService, transactionManager);
        when(eventRepository.save(any())).thenAnswer(i -> {
            recorded.add(i.getArgument(0));
            return i.getArgument(0);
        });
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(queryService.itinerary(any())).thenReturn(List.of(
                new ItinerarySegment("DEL", "BOM", "2026-12-02T09:00", "2026-12-02T11:10", "IndiGo", "6E-201")));
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
                .status(PlatformBookingStatus.TICKETED).pnr("ABC123").ticketNumbers(List.of("0981234567890"))
                .displayed(new BigDecimal("5400.00")).gross(new BigDecimal("5500.00")).base(new BigDecimal("4500.00"))
                .debitEntryId("WE-DEBIT").mirrorStatus(clientId == null ? MirrorStatus.NONE : MirrorStatus.PENDING)
                .paxEnc(paxCodec.encrypt(Map.of("passengers", List.of(pax), "contactEmail", "a@b.c", "contactPhone", "9")))
                .build();
        when(repository.findById("PB1")).thenReturn(Optional.of(b));
        when(repository.findByIdForUpdate("PB1")).thenReturn(Optional.of(b));
        return b;
    }

    @Test
    void mirrorCreatedOnlyWithAClient() {
        PlatformBooking starter = ticketed(null);

        service.sync("PB1");

        verifyNoInteractions(tenantService);
        assertThat(starter.getMirrorStatus()).isEqualTo(MirrorStatus.NONE);

        PlatformBooking crm = ticketed("C1");
        when(tenantService.create(any(), anyList(), anyList())).thenReturn("B1");

        service.sync("PB1");

        ArgumentCaptor<List<PaxInput>> pax = ArgumentCaptor.forClass(List.class);
        verify(tenantService).create(eq(crm), anyList(), pax.capture());
        assertThat(pax.getValue()).singleElement().satisfies(p -> assertThat(p.getLastName()).isEqualTo("Sharma"));
        verify(tenantService, never()).cancel(anyString(), any());
        assertThat(crm.getMirrorStatus()).isEqualTo(MirrorStatus.DONE);
        assertThat(crm.getTenantBookingId()).isEqualTo("B1");
        assertThat(recorded).extracting(PlatformBookingEvent::getType).contains("MIRRORED");
    }

    @Test
    void mirrorFailureLeavesMoneyAndMarksFailed() {
        PlatformBooking b = ticketed("C1");
        when(tenantService.create(any(), anyList(), anyList()))
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
        when(tenantService.create(any(), anyList(), anyList())).thenAnswer(i -> {
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
        when(tenantService.create(any(), anyList(), anyList())).thenAnswer(i -> {
            seen.add("create@" + TenantContext.getTenantId());
            return "B1";
        });
        doAnswer(i -> {
            CustomUserPrincipal actor = SecurityContextUtil.getCurrentUserOrThrow();
            seen.add("cancel@" + TenantContext.getTenantId() + " by " + actor.userId() + "/" + actor.tenantId()
                    + " system=" + SystemActor.isActive());
            return null;
        }).when(tenantService).cancel(eq("B1"), eq(b));

        service.sync("PB1");

        assertThat(seen).containsExactly("create@T1", "cancel@T1 by system/T1 system=true");
        assertThat(b.getMirrorStatus()).isEqualTo(MirrorStatus.DONE);
        assertThat(TenantContext.getTenantId()).isEqualTo("T-OTHER");
        assertThat(SystemActor.isActive()).isFalse();
        assertThat(SecurityContextUtil.getCurrentUser()).isEmpty();
    }

    @Test
    void aMirrorCancelFailureIsRecordedAndTheContextStillRestored() {
        PlatformBooking b = ticketed("C1");
        b.setStatus(PlatformBookingStatus.CANCELLED);
        given(tenantService.create(any(), anyList(), anyList())).willReturn("B1");
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

    @Test
    void anAgentResolvesFromTheLeadsServiceOrTheLead() {
        when(tenantService.leadAgent("T1", "S1", "L1")).thenReturn(Optional.of("A9"));

        assertThat(service.hasLeadAgent("T1", "S1", "L1")).isTrue();
        assertThat(service.hasLeadAgent("T1", null, null)).isFalse();
    }

    /** A hotel booking for a client is mirrored from its stay (a HOTEL_VOUCHER), never from flight legs. */
    @Test
    void aHotelBookingIsMirroredFromItsStay() {
        PlatformBooking hotel = ticketed("C1");
        hotel.setProduct(com.voyra.crm.enums.PricingProduct.HOTEL);
        com.voyra.crm.dto.HotelStaySummary stay = new com.voyra.crm.dto.HotelStaySummary("Sea Breeze Resort", "Goa",
                java.time.LocalDate.of(2026, 12, 1), java.time.LocalDate.of(2026, 12, 3), 2, 1, "Breakfast included");
        when(queryService.hotel(hotel)).thenReturn(stay);
        when(tenantService.createHotel(any(), any(), anyList())).thenReturn("B1");

        service.sync("PB1");

        verify(tenantService).createHotel(eq(hotel), eq(stay), anyList());
        verify(tenantService, never()).create(any(), anyList(), anyList());
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
        when(tenantService.createHotel(any(), any(), anyList())).thenReturn("B1");

        service.sync("PB1");

        verify(tenantService).cancel("B1", hotel);
        assertThat(hotel.getMirrorStatus()).isEqualTo(MirrorStatus.DONE);
    }
}
