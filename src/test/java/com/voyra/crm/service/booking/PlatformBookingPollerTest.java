package com.voyra.crm.service.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.entity.PlatformBookingEvent;
import com.voyra.crm.entity.WalletEntry;
import com.voyra.crm.enums.PlatformBookingStatus;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.enums.ReceivableReason;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.WalletEntryType;
import com.voyra.crm.exception.SupplierTimeoutException;
import com.voyra.crm.models.booking.BookResult;
import com.voyra.crm.models.booking.SupplierBookingStatus;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.PlatformBookingEventRepository;
import com.voyra.crm.repository.PlatformBookingRepository;
import com.voyra.crm.repository.PlatformSupplierCredentialRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.repository.WalletEntryRepository;
import com.voyra.crm.repository.WalletRepository;
import com.voyra.crm.service.AgencyPricingService;
import com.voyra.crm.service.NotificationService;
import com.voyra.crm.service.PlatformAuditService;
import com.voyra.crm.service.PlatformSettingService;
import com.voyra.crm.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlatformBookingPollerTest {

    private static final String T = "T1";
    private static final String KEY = "dGVzdC1vbmx5LWFlcy1rZXktMzItYnl0ZXMtbG9uZyE=";

    @Mock private PlatformBookingRepository bookingRepository;
    @Mock private PlatformBookingEventRepository eventRepository;
    @Mock private FlightBookingProvider provider;
    @Mock private AgencyPriceQuoter quoter;
    @Mock private AgencyPricingService pricingService;
    @Mock private WalletService walletService;
    @Mock private WalletRepository walletRepository;
    @Mock private WalletEntryRepository walletEntryRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private PlatformSupplierCredentialRepository credentialRepository;
    @Mock private NotificationService notificationService;
    @Mock private PlatformAuditService platformAuditService;
    @Mock private PlatformSettingService settings;
    @Mock private PlatformTransactionManager transactionManager;

    private final FlightBookingProviderConfig config = new FlightBookingProviderConfig();
    private final Map<String, PlatformBooking> rows = new HashMap<>();
    private final List<PlatformBookingEvent> savedEvents = new ArrayList<>();
    /** Wallet entries the mocked WalletService "posted", by idempotency key. */
    private final Map<String, WalletEntry> walletEntries = new HashMap<>();
    private PlatformBookingPoller poller;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        PaxCodec paxCodec = new PaxCodec(objectMapper, KEY);
        PlatformBookingEventRecorder events = new PlatformBookingEventRecorder(eventRepository, paxCodec);
        PlatformBookingService service = new PlatformBookingService(bookingRepository, provider, config, quoter, pricingService,
                walletService, walletRepository, tenantRepository, agentRepository, credentialRepository, notificationService,
                events, paxCodec, objectMapper, transactionManager, walletEntryRepository, platformAuditService, settings);
        poller = new PlatformBookingPoller(bookingRepository, eventRepository, provider, config, service, events,
                notificationService, transactionManager);

        lenient().when(bookingRepository.save(any())).thenAnswer(i -> {
            PlatformBooking b = i.getArgument(0);
            rows.put(b.getId(), b);
            return b;
        });
        lenient().when(bookingRepository.findByIdForUpdate(anyString())).thenAnswer(i -> Optional.ofNullable(rows.get(i.<String>getArgument(0))));
        lenient().when(bookingRepository.findByStatusIn(any())).thenAnswer(i -> {
            Collection<PlatformBookingStatus> wanted = i.getArgument(0);
            return rows.values().stream().filter(b -> wanted.contains(b.getStatus()))
                    .map(b -> b.toBuilder().build()).toList(); // detached copies, as a read outside a transaction gives
        });
        lenient().when(eventRepository.save(any())).thenAnswer(i -> {
            savedEvents.add(i.getArgument(0));
            return i.getArgument(0);
        });
        lenient().when(eventRepository.findByPlatformBookingIdOrderByCreatedAtAsc(anyString())).thenAnswer(i -> savedEvents.stream()
                .filter(e -> e.getPlatformBookingId().equals(i.getArgument(0))).toList());
        lenient().when(walletService.debitHeld(anyString(), any(), anyString(), anyString(), anyString()))
                .thenReturn(WalletEntry.builder().id("D1").tenantId(T).build());
        lenient().when(walletService.debitOrReceivable(any(), any(), any(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(WalletEntry.builder().id("D2").tenantId(T).build());
        lenient().when(walletService.releaseHold(anyString(), anyString())).thenAnswer(i -> {
            WalletEntry r = WalletEntry.builder().id("R-" + i.getArgument(0)).tenantId(T).type(WalletEntryType.HOLD_RELEASE)
                    .relatedEntryId(i.getArgument(0)).idempotencyKey(i.getArgument(1)).build();
            walletEntries.put(r.getIdempotencyKey(), r);
            return r;
        });
        lenient().when(walletEntryRepository.findByIdempotencyKey(anyString()))
                .thenAnswer(i -> Optional.ofNullable(walletEntries.get(i.<String>getArgument(0))));
        lenient().when(walletEntryRepository.existsByRelatedEntryIdAndType(anyString(), eq(WalletEntryType.HOLD_RELEASE)))
                .thenAnswer(i -> walletEntries.values().stream().anyMatch(e -> e.getType() == WalletEntryType.HOLD_RELEASE
                        && i.<String>getArgument(0).equals(e.getRelatedEntryId())));
        lenient().when(bookingRepository.findByStatusAndResolvedFailedAtAfter(eq(PlatformBookingStatus.FAILED), any())).thenAnswer(i -> {
            Instant after = i.getArgument(1);
            return rows.values().stream().filter(b -> b.getStatus() == PlatformBookingStatus.FAILED
                    && b.getResolvedFailedAt() != null && b.getResolvedFailedAt().isAfter(after)).map(b -> b.toBuilder().build()).toList();
        });
    }

    private void committedHold(String id) {
        walletEntries.put("pb-hold:" + id, WalletEntry.builder().id("H1").tenantId(T).type(WalletEntryType.HOLD)
                .idempotencyKey("pb-hold:" + id).build());
    }

    private void event(String id, String type, Instant at) {
        savedEvents.add(PlatformBookingEvent.builder().id("E-" + type + id).platformBookingId(id).type(type).createdAt(at).build());
    }

    /** A row whose book call timed out: BOOKING, hold H1 open, no supplier booking id yet. */
    private PlatformBooking bookingRow(String id, Instant bookingAt) {
        PlatformBooking b = PlatformBooking.builder()
                .id(id).tenantId(T).product(PricingProduct.FLIGHT).status(PlatformBookingStatus.BOOKING)
                .environment(SupplierEnvironment.MOCK).supplierReviewId("rv-" + id)
                .gross(new BigDecimal("5000.00")).base(new BigDecimal("4250.00")).displayed(new BigDecimal("4500.00"))
                .agencyCommission(new BigDecimal("100.00")).tds(BigDecimal.ZERO).voyraNet(new BigDecimal("27.50"))
                .holdEntryId("H1").createdAt(bookingAt.minus(2, ChronoUnit.MINUTES)).build();
        rows.put(id, b);
        savedEvents.add(PlatformBookingEvent.builder().id("E-" + id).platformBookingId(id).type("BOOKING").createdAt(bookingAt).build());
        return b;
    }

    private static BookResult pending() {
        return new BookResult(null, SupplierBookingStatus.PENDING, null, List.of(), null, null);
    }

    @Test
    void pendingThenTicketedOnPollDebitsOnce() {
        bookingRow("B1", Instant.now());
        when(provider.bookingDetails("rv-B1")).thenReturn(pending())
                .thenReturn(new BookResult("rv-B1", SupplierBookingStatus.TICKETED, "PNR1", List.of("TKT-1"), "Ticketed", null));

        poller.poll();
        assertThat(rows.get("B1").getStatus()).isEqualTo(PlatformBookingStatus.BOOKING);
        assertThat(rows.get("B1").getPollCount()).isEqualTo(1);
        assertThat(rows.get("B1").getLastPolledAt()).isNotNull();

        poller.poll();
        poller.poll(); // TICKETED is not polled again

        assertThat(rows.get("B1").getStatus()).isEqualTo(PlatformBookingStatus.TICKETED);
        assertThat(rows.get("B1").getPnr()).isEqualTo("PNR1");
        verify(provider, times(2)).bookingDetails("rv-B1");
        verify(walletService, times(1)).debitHeld("H1", new BigDecimal("4500.00"), "pb-debit:B1", "PLATFORM_BOOKING", "B1");
        verify(walletService, times(1)).credit(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void pollsBySupplierBookingIdWhenKnown() {
        bookingRow("B1", Instant.now()).setSupplierBookingId("SB-9");
        when(provider.bookingDetails("SB-9")).thenReturn(pending());

        poller.poll();

        verify(provider).bookingDetails("SB-9");
    }

    @Test
    void failedOnPollReleasesTheHold() {
        bookingRow("B1", Instant.now());
        when(provider.bookingDetails("rv-B1")).thenReturn(
                new BookResult(null, SupplierBookingStatus.FAILED, null, List.of(), "Fare sold out", null));

        poller.poll();

        assertThat(rows.get("B1").getStatus()).isEqualTo(PlatformBookingStatus.FAILED);
        assertThat(rows.get("B1").getResolvedFailedAt()).isNull(); // a supplier FAILED is final; only an admin's is re-checked
        verify(walletService).releaseHold("H1", "pb-release:B1");
    }

    @Test
    void noAnswerAfterMaxMinutesBecomesUnknownAndKeepsHold() {
        bookingRow("B1", Instant.now().minus(31, ChronoUnit.MINUTES));
        when(provider.bookingDetails("rv-B1")).thenThrow(new SupplierTimeoutException("timed out", null));

        poller.poll();

        assertThat(rows.get("B1").getStatus()).isEqualTo(PlatformBookingStatus.UNKNOWN);
        assertThat(rows.get("B1").getHoldEntryId()).isEqualTo("H1");
        assertThat(savedEvents).extracting(PlatformBookingEvent::getType).contains("UNKNOWN");
        verify(walletService, never()).releaseHold(any(), any());
        verify(walletService, never()).debitHeld(any(), any(), any(), any(), any());
        verify(notificationService).platformAlert(contains("B1"), anyString());
    }

    @Test
    void pendingWithinMaxMinutesStaysBooking() {
        bookingRow("B1", Instant.now().minus(29, ChronoUnit.MINUTES));
        when(provider.bookingDetails("rv-B1")).thenReturn(pending());

        poller.poll();

        assertThat(rows.get("B1").getStatus()).isEqualTo(PlatformBookingStatus.BOOKING);
        verify(notificationService, never()).platformAlert(anyString(), anyString());
    }

    @Test
    void unknownIsPolledEveryTenMinutesAndCanStillTicket() {
        PlatformBooking b = bookingRow("B1", Instant.now().minus(2, ChronoUnit.HOURS));
        b.setStatus(PlatformBookingStatus.UNKNOWN);
        b.setLastPolledAt(Instant.now().minus(3, ChronoUnit.MINUTES));

        poller.poll();
        verify(provider, never()).bookingDetails(anyString());

        rows.get("B1").setLastPolledAt(Instant.now().minus(11, ChronoUnit.MINUTES));
        when(provider.bookingDetails("rv-B1")).thenReturn(
                new BookResult("SB-1", SupplierBookingStatus.TICKETED, "PNR1", List.of("TKT-1"), "Ticketed", null));
        poller.poll();

        assertThat(rows.get("B1").getStatus()).isEqualTo(PlatformBookingStatus.TICKETED);
        verify(walletService, times(1)).debitHeld("H1", new BigDecimal("4500.00"), "pb-debit:B1", "PLATFORM_BOOKING", "B1");
    }

    @Test
    void oneRowFailingDoesNotStopTheOthers() {
        bookingRow("B1", Instant.now());
        bookingRow("B2", Instant.now());
        when(provider.bookingDetails("rv-B1")).thenReturn(
                new BookResult("SB-1", SupplierBookingStatus.TICKETED, "PNR1", List.of("TKT-1"), "Ticketed", null));
        when(provider.bookingDetails("rv-B2")).thenReturn(
                new BookResult("SB-2", SupplierBookingStatus.TICKETED, "PNR2", List.of("TKT-2"), "Ticketed", null));
        when(walletService.debitHeld(anyString(), any(), anyString(), anyString(), anyString())).thenAnswer(i -> {
            if (i.<String>getArgument(2).endsWith("B1")) {
                throw new IllegalStateException("wallet locked");
            }
            return WalletEntry.builder().id("D2").tenantId(T).type(WalletEntryType.BOOKING_DEBIT).build();
        });

        poller.poll();

        assertThat(rows.get("B2").getStatus()).isEqualTo(PlatformBookingStatus.TICKETED);
    }

    // ---- review fixes ----

    private static BookResult ticketed() {
        return new BookResult("SB-1", SupplierBookingStatus.TICKETED, "PNR1", List.of("TKT-1"), "Ticketed", null);
    }

    @Test
    void ticketAfterAnAdminResolvedFailedIsDebitedOnceViaReceivable() {
        PlatformBooking b = bookingRow("B1", Instant.now().minus(3, ChronoUnit.HOURS));
        b.setStatus(PlatformBookingStatus.FAILED);
        b.setResolvedFailedAt(Instant.now().minus(1, ChronoUnit.HOURS));
        walletEntries.put("pb-release:B1", WalletEntry.builder().id("R1").type(WalletEntryType.HOLD_RELEASE)
                .relatedEntryId("H1").idempotencyKey("pb-release:B1").build());
        when(provider.bookingDetails("rv-B1"))
                .thenReturn(new BookResult(null, SupplierBookingStatus.FAILED, null, List.of(), "No booking", null))
                .thenReturn(ticketed());

        poller.poll();
        assertThat(rows.get("B1").getStatus()).isEqualTo(PlatformBookingStatus.FAILED);
        poller.poll(); // within 10 minutes of the last poll: not asked again
        verify(provider, times(1)).bookingDetails("rv-B1");

        rows.get("B1").setLastPolledAt(Instant.now().minus(11, ChronoUnit.MINUTES));
        poller.poll();
        poller.poll(); // TICKETED is not polled again

        assertThat(rows.get("B1").getStatus()).isEqualTo(PlatformBookingStatus.TICKETED);
        verify(walletService, times(1)).debitOrReceivable(T, WalletEntryType.BOOKING_DEBIT, new BigDecimal("4500.00"),
                "pb-debit:B1", "PLATFORM_BOOKING", "B1", ReceivableReason.LATE_TICKET, "H1");
        verify(walletService, never()).debitHeld(any(), any(), any(), any(), any());
        verify(walletService, never()).releaseHold(any(), any());
    }

    @Test
    void aBookingResolvedFailedMoreThan72HoursAgoIsNotPolled() {
        PlatformBooking b = bookingRow("B1", Instant.now().minus(80, ChronoUnit.HOURS));
        b.setStatus(PlatformBookingStatus.FAILED);
        b.setResolvedFailedAt(Instant.now().minus(73, ChronoUnit.HOURS));

        poller.poll();

        verify(provider, never()).bookingDetails(anyString());
    }

    @Test
    void aFailingApplyStillRecordsThePollAndEscalates() {
        bookingRow("B1", Instant.now().minus(31, ChronoUnit.MINUTES));
        when(provider.bookingDetails("rv-B1")).thenReturn(ticketed());
        when(walletService.debitHeld(anyString(), any(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("wallet row locked"));

        poller.poll();

        PlatformBooking b = rows.get("B1");
        assertThat(b.getPollCount()).isEqualTo(1);
        assertThat(b.getLastPolledAt()).isNotNull();
        assertThat(b.getStatus()).isEqualTo(PlatformBookingStatus.UNKNOWN);
    }

    @Test
    void sweepReleasesTheOrphanHoldOfAnExpiredReview() {
        PlatformBooking b = bookingRow("B1", Instant.now().minus(1, ChronoUnit.HOURS));
        b.setStatus(PlatformBookingStatus.REVIEWING);
        b.setHoldEntryId(null);
        b.setReviewExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        committedHold("B1");
        when(bookingRepository.findExpiredReviewsWithOpenHold(any(), any())).thenReturn(List.of(b.toBuilder().build()));

        poller.poll();

        assertThat(rows.get("B1").getStatus()).isEqualTo(PlatformBookingStatus.ABORTED);
        assertThat(rows.get("B1").getAbortReason()).isEqualTo("Review expired");
        verify(walletService).releaseHold("H1", "pb-release:B1");
        verify(provider, never()).bookingDetails(anyString());
    }

    @Test
    void sweepReleasesAHeldBookingThatWasNeverSent() {
        PlatformBooking b = bookingRow("B1", Instant.now());
        savedEvents.clear();
        b.setStatus(PlatformBookingStatus.HELD);
        event("B1", "HELD", Instant.now().minus(11, ChronoUnit.MINUTES));
        committedHold("B1");

        poller.poll();

        assertThat(rows.get("B1").getStatus()).isEqualTo(PlatformBookingStatus.ABORTED);
        assertThat(rows.get("B1").getAbortReason()).isEqualTo("Not sent to supplier");
        verify(walletService).releaseHold("H1", "pb-release:B1");
    }

    @Test
    void sweepLeavesAFreshHeldBookingAlone() {
        PlatformBooking b = bookingRow("B1", Instant.now());
        savedEvents.clear();
        b.setStatus(PlatformBookingStatus.HELD);
        event("B1", "HELD", Instant.now().minus(2, ChronoUnit.MINUTES));
        committedHold("B1");

        poller.poll();

        assertThat(rows.get("B1").getStatus()).isEqualTo(PlatformBookingStatus.HELD);
        verify(walletService, never()).releaseHold(any(), any());
    }

    @Test
    void pollRefusesToRunInsideATransaction() {
        bookingRow("B1", Instant.now());
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> poller.poll()).isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verify(provider, never()).bookingDetails(anyString());
    }
}
