package com.voyra.crm.service.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.entity.PlatformBookingEvent;
import com.voyra.crm.entity.Wallet;
import com.voyra.crm.entity.WalletEntry;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.PlatformBookingStatus;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.enums.ReceivableReason;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.enums.WalletEntryType;
import com.voyra.crm.enums.WalletStatus;
import com.voyra.crm.exception.BookingConflictException;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.exception.SupplierException;
import com.voyra.crm.exception.SupplierTimeoutException;
import com.voyra.crm.models.booking.BookResult;
import com.voyra.crm.models.booking.CancelQuote;
import com.voyra.crm.models.booking.CancelResult;
import com.voyra.crm.models.booking.SupplierBookingStatus;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.PlatformBookingEventRepository;
import com.voyra.crm.repository.PlatformBookingRepository;
import com.voyra.crm.repository.PlatformSupplierCredentialRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.repository.WalletEntryRepository;
import com.voyra.crm.repository.WalletRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.service.AgencyPricingService;
import com.voyra.crm.service.NotificationService;
import com.voyra.crm.service.PlatformAuditService;
import com.voyra.crm.service.PlatformSettingService;
import com.voyra.crm.service.WalletService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Whole-booking cancellation (spec 7.5). The ticketed row: gross 5000, displayed 4500, Voyra earned 127.50,
 * agency commission 100 with TDS 5 (95 credited net), voyra_net 127.50 - 500 - 100 = -472.50.
 */
@ExtendWith(MockitoExtension.class)
class PlatformBookingCancelTest {

    private static final String T = "T1";
    private static final String ID = "PB1";
    private static final String REF = "PLATFORM_BOOKING";
    private static final String KEY = "dGVzdC1vbmx5LWFlcy1rZXktMzItYnl0ZXMtbG9uZyE=";

    @Mock private PlatformBookingRepository bookingRepository;
    @Mock private PlatformBookingEventRepository eventRepository;
    @Mock private FlightBookingProvider provider;
    @Mock private HotelBookingProvider hotelProvider;
    @Mock private AgencyPriceQuoter quoter;
    @Mock private AgencyPricingService pricingService;
    @Mock private WalletService walletService;
    @Mock private WalletRepository walletRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private PlatformSupplierCredentialRepository credentialRepository;
    @Mock private NotificationService notificationService;
    @Mock private PlatformBookingMirrorService mirrorService;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private WalletEntryRepository walletEntryRepository;
    @Mock private PlatformAuditService platformAuditService;
    @Mock private PlatformSettingService settings;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final PaxCodec paxCodec = new PaxCodec(objectMapper, KEY);
    private final Map<String, PlatformBooking> rows = new HashMap<>();
    private final List<PlatformBookingEvent> savedEvents = new ArrayList<>();
    private PlatformBookingService service;
    private Wallet wallet = Wallet.builder().id("W1").tenantId(T).status(WalletStatus.OPEN).balance(new BigDecimal("1000.00"))
            .held(BigDecimal.ZERO).build();

    private final HotelBookingProviderConfig hotelConfig = new HotelBookingProviderConfig();

    private final FlightBookingProviderConfig flightConfig = new FlightBookingProviderConfig();

    private BookingProviderRegistry registry() {
        return new BookingProviderRegistry(provider, hotelProvider, flightConfig, hotelConfig);
    }

    @BeforeEach
    void setUp() {
        service = new PlatformBookingService(bookingRepository, registry(), quoter, pricingService,
                walletService, walletRepository, tenantRepository, agentRepository, credentialRepository, notificationService,
                new PlatformBookingEventRecorder(eventRepository, paxCodec), paxCodec, objectMapper, transactionManager,
                walletEntryRepository, platformAuditService, settings, mirrorService);
        lenient().when(bookingRepository.save(any())).thenAnswer(i -> {
            PlatformBooking b = i.getArgument(0);
            rows.put(b.getId(), b);
            return b;
        });
        lenient().when(bookingRepository.findByIdForUpdate(anyString())).thenAnswer(i -> Optional.ofNullable(rows.get(i.<String>getArgument(0))));
        lenient().when(bookingRepository.findByIdAndTenantId(anyString(), anyString())).thenAnswer(i -> Optional.ofNullable(rows.get(i.<String>getArgument(0)))
                .filter(b -> b.getTenantId().equals(i.getArgument(1))));
        lenient().when(eventRepository.save(any())).thenAnswer(i -> {
            savedEvents.add(i.getArgument(0));
            return i.getArgument(0);
        });
        lenient().when(eventRepository.findFirstByPlatformBookingIdAndTypeOrderByCreatedAtDesc(anyString(), anyString()))
                .thenAnswer(i -> savedEvents.stream()
                        .filter(e -> e.getPlatformBookingId().equals(i.getArgument(0)) && e.getType().equals(i.getArgument(1)))
                        .reduce((first, second) -> second));
        lenient().when(walletRepository.findByTenantId(T)).thenAnswer(i -> Optional.of(wallet));
        lenient().when(settings.voyraCancelFee()).thenReturn(BigDecimal.ZERO);
        lenient().when(walletService.credit(anyString(), any(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(WalletEntry.builder().id("C1").build());
        lenient().when(walletService.debitOrReceivable(anyString(), any(), any(), anyString(), anyString(), anyString(), any()))
                .thenReturn(WalletEntry.builder().id("RC1").build());
        rows.put(ID, ticketedRow(PlatformBookingStatus.TICKETED));
        loginAs("OWNER1", T);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void loginAs(String userId, String tenantId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal(userId, userId + "@agency.test", UserType.AGENCY_OWNER, tenantId), null));
    }

    private static void loginAsPlatformAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("SA1", "root", UserType.SUPER_ADMIN, null), null));
    }

    private static PlatformBooking ticketedRow(PlatformBookingStatus status) {
        return PlatformBooking.builder().id(ID).tenantId(T).product(PricingProduct.FLIGHT).status(status)
                .environment(SupplierEnvironment.MOCK).supplierReviewId("rv-1").supplierBookingId("SB-1").pnr("PNR123")
                .gross(new BigDecimal("5000.00")).base(new BigDecimal("4250.00")).displayed(new BigDecimal("4500.00"))
                .voyraEarned(new BigDecimal("127.50")).agencyCommission(new BigDecimal("100.00")).tds(new BigDecimal("5.00"))
                .voyraNet(new BigDecimal("-472.50")).holdEntryId("H1").debitEntryId("D1")
                .pricingSnapshot(new LinkedHashMap<>(Map.of("pricingId", "P1"))).createdAt(Instant.now()).build();
    }

    private void supplierRefunds(String refund) {
        BigDecimal r = refund == null ? null : new BigDecimal(refund);
        lenient().when(provider.cancelQuote("SB-1")).thenReturn(new CancelQuote(r, "quoted"));
        lenient().when(provider.cancel("SB-1")).thenReturn(new CancelResult(true, r, "Cancelled"));
    }

    private List<String> eventTypes() {
        return savedEvents.stream().map(PlatformBookingEvent::getType).toList();
    }

    private void verifyNothingPosted() {
        verify(walletService, never()).credit(anyString(), any(), any(), anyString(), anyString(), anyString(), anyString());
        verify(walletService, never()).debitOrReceivable(anyString(), any(), any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void cancelPostsRefundThenRecall() {
        supplierRefunds("4000.00");

        PlatformBooking b = service.cancel(ID, "ck-1");

        InOrder order = inOrder(provider, walletService);
        order.verify(provider).cancel("SB-1");
        order.verify(walletService).credit(eq(T), eq(WalletEntryType.REFUND_CREDIT), eq(new BigDecimal("4000.00")),
                eq("pb-refund:" + ID), eq(REF), eq(ID), contains("PNR123"));
        order.verify(walletService).debitOrReceivable(T, WalletEntryType.COMMISSION_RECALL, new BigDecimal("95.00"),
                "pb-recall:" + ID, REF, ID, ReceivableReason.CANCEL_NET_NEGATIVE);
        assertThat(b.getStatus()).isEqualTo(PlatformBookingStatus.CANCELLED);
        assertThat(b.getCancelRefund()).isEqualByComparingTo("4000.00");
        assertThat(b.getCancelledAt()).isNotNull();
        verify(notificationService).bookingCancelled(b, false);
        assertThat(eventTypes()).containsSubsequence("CANCEL_REQUESTED", "CANCELLED");
    }

    /** A cancelled booking whose CRM mirror was written goes back to PENDING; the mirror cancel runs after the refund committed. */
    @Test
    void cancelSchedulesTheMirrorCancelAfterTheRefund() {
        supplierRefunds("4000.00");
        rows.get(ID).setMirrorStatus(com.voyra.crm.enums.MirrorStatus.DONE);

        PlatformBooking b = service.cancel(ID, "ck-1");

        assertThat(b.getMirrorStatus()).isEqualTo(com.voyra.crm.enums.MirrorStatus.PENDING);
        InOrder order = inOrder(walletService, mirrorService);
        order.verify(walletService).credit(eq(T), eq(WalletEntryType.REFUND_CREDIT), any(), anyString(), anyString(), anyString(), anyString());
        order.verify(mirrorService).sync(ID);
    }

    @Test
    void refundIsNetOfTheVoyraFeeAndCappedAtDisplayed() {
        when(settings.voyraCancelFee()).thenReturn(new BigDecimal("250.00"));
        supplierRefunds("6000.00");

        PlatformBooking b = service.cancel(ID, "ck-1");

        // 6000 - 250 = 5750, capped at the 4500 the agency paid
        verify(walletService).credit(eq(T), eq(WalletEntryType.REFUND_CREDIT), eq(new BigDecimal("4500.00")),
                eq("pb-refund:" + ID), eq(REF), eq(ID), anyString());
        assertThat(b.getCancelRefund()).isEqualByComparingTo("4500.00");
    }

    @Test
    void zeroRefundStillRecallsUnderTheCancelNetNegativeReason() {
        when(settings.voyraCancelFee()).thenReturn(new BigDecimal("250.00"));
        supplierRefunds("100.00"); // below the fee: refund 0, net = -95

        PlatformBooking b = service.cancel(ID, "ck-1");

        // no zero credit; the recall goes to debitOrReceivable, which opens the receivable when available falls short
        verify(walletService, never()).credit(anyString(), any(), any(), anyString(), anyString(), anyString(), anyString());
        verify(walletService).debitOrReceivable(T, WalletEntryType.COMMISSION_RECALL, new BigDecimal("95.00"),
                "pb-recall:" + ID, REF, ID, ReceivableReason.CANCEL_NET_NEGATIVE);
        assertThat(b.getStatus()).isEqualTo(PlatformBookingStatus.CANCELLED);
        assertThat(b.getCancelRefund()).isEqualByComparingTo("0");
    }

    @Test
    void noCommissionMeansNoRecall() {
        rows.get(ID).setAgencyCommission(BigDecimal.ZERO);
        rows.get(ID).setTds(BigDecimal.ZERO);
        supplierRefunds("4000.00");

        service.cancel(ID, "ck-1");

        verify(walletService).credit(eq(T), eq(WalletEntryType.REFUND_CREDIT), eq(new BigDecimal("4000.00")),
                anyString(), anyString(), anyString(), anyString());
        verify(walletService, never()).debitOrReceivable(anyString(), any(), any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void supplierRefusalPostsNothing() {
        when(provider.cancelQuote("SB-1")).thenReturn(new CancelQuote(new BigDecimal("4000.00"), "quoted"));
        when(provider.cancel("SB-1")).thenReturn(new CancelResult(false, null, "Ticket already flown"));

        assertThatThrownBy(() -> service.cancel(ID, "ck-1"))
                .isInstanceOfSatisfying(BookingConflictException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("CANCEL_REFUSED");
                    // readiness: the agency gets the fixed sentence; the supplier's words stay on the event for Voyra
                    assertThat(e.getMessage()).isEqualTo("The supplier refused the cancellation");
                });
        assertThat(savedEvents.stream().filter(ev -> ev.getType().equals("CANCEL_REFUSED")).findFirst().orElseThrow()
                .getPayload()).containsEntry("message", "Ticket already flown");

        verifyNothingPosted();
        assertThat(rows.get(ID).getStatus()).isEqualTo(PlatformBookingStatus.TICKETED);
        assertThat(rows.get(ID).getCancelledAt()).isNull();
        assertThat(eventTypes()).contains("CANCEL_REFUSED");

        assertThatThrownBy(() -> service.cancel(ID, "ck-2")).isInstanceOf(BookingConflictException.class);
        verify(provider, times(2)).cancel("SB-1");
    }

    @Test
    void supplierTimeoutKeepsTheBookingCancellingAndAlerts() {
        when(provider.cancelQuote("SB-1")).thenReturn(new CancelQuote(new BigDecimal("4000.00"), "quoted"));
        when(provider.cancel("SB-1")).thenThrow(new SupplierTimeoutException("Tripjack cancel timed out", null));

        // accepted-pending: answers normally with the CANCELLING booking, so the agency is not told to retry
        assertThat(service.cancel(ID, "ck-1").getStatus()).isEqualTo(PlatformBookingStatus.CANCELLING);

        // the supplier may have cancelled: no posting, no second submission, an admin settles it
        assertThat(rows.get(ID).getStatus()).isEqualTo(PlatformBookingStatus.CANCELLING);
        assertThat(eventTypes()).contains("CANCEL_NO_ANSWER");
        verify(notificationService, times(1)).platformAlert(contains(ID), anyString());
        verifyNothingPosted();
        assertThat(service.cancel(ID, "ck-1").getStatus()).isEqualTo(PlatformBookingStatus.CANCELLING);
        verify(provider, times(1)).cancel("SB-1");
    }

    @Test
    void anyRuntimeErrorFromTheSupplierCancelIsTreatedAsNoAnswer() {
        when(provider.cancelQuote("SB-1")).thenReturn(new CancelQuote(new BigDecimal("4000.00"), "quoted"));
        when(provider.cancel("SB-1")).thenThrow(new IllegalArgumentException("unexpected body"));

        assertThatThrownBy(() -> service.cancel(ID, "ck-1")).isInstanceOf(SupplierException.class);
        assertThat(rows.get(ID).getStatus()).isEqualTo(PlatformBookingStatus.CANCELLING);
        verifyNothingPosted();
    }

    @Test
    void aQuoteErrorRevertsToTicketedBecauseNothingWasSubmitted() {
        when(provider.cancelQuote("SB-1")).thenThrow(new SupplierException("Tripjack cancel quote call failed", null));

        assertThatThrownBy(() -> service.cancel(ID, "ck-1")).isInstanceOf(SupplierException.class);
        assertThat(rows.get(ID).getStatus()).isEqualTo(PlatformBookingStatus.TICKETED);
        verify(provider, never()).cancel(anyString());
    }

    @Test
    void aSecondCancelWhileCancellingNeverReachesTheSupplier() {
        when(provider.cancelQuote("SB-1")).thenReturn(new CancelQuote(new BigDecimal("4000.00"), "quoted"));
        when(provider.cancel("SB-1")).thenAnswer(i -> {
            // a double click / second tab arrives while the first request waits on the supplier
            assertThat(service.cancel(ID, "ck-1").getStatus()).isEqualTo(PlatformBookingStatus.CANCELLING);
            assertThatThrownBy(() -> service.cancel(ID, "ck-2"))
                    .isInstanceOfSatisfying(BookingConflictException.class, e -> assertThat(e.getCode()).isEqualTo("CANCEL_IN_PROGRESS"));
            assertThatThrownBy(() -> service.cancelQuote(ID))
                    .isInstanceOfSatisfying(BookingConflictException.class, e -> assertThat(e.getCode()).isEqualTo("CANCEL_IN_PROGRESS"));
            return new CancelResult(true, new BigDecimal("4000.00"), "Cancelled");
        });

        assertThat(service.cancel(ID, "ck-1").getStatus()).isEqualTo(PlatformBookingStatus.CANCELLED);
        verify(provider, times(1)).cancel("SB-1");
        verify(provider, times(1)).cancelQuote("SB-1");
        verify(walletService, times(1)).credit(anyString(), any(), any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void aFailedSettlementAfterAcceptanceStaysCancellingAndAlerts() {
        supplierRefunds("4000.00");
        when(walletService.credit(anyString(), any(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> service.cancel(ID, "ck-1")).isInstanceOf(IllegalStateException.class);

        assertThat(rows.get(ID).getStatus()).isEqualTo(PlatformBookingStatus.CANCELLING);
        PlatformBookingEvent accepted = savedEvents.stream().filter(e -> e.getType().equals("CANCEL_ACCEPTED")).findFirst().orElseThrow();
        assertThat(accepted.getPayload()).containsEntry("message", "Cancelled");
        assertThat(new BigDecimal(String.valueOf(accepted.getPayload().get("refund")))).isEqualByComparingTo("4000.00");
        verify(notificationService).platformAlert(contains(ID), contains("4000"));
        // a retry must not submit the cancellation again
        assertThat(service.cancel(ID, "ck-1").getStatus()).isEqualTo(PlatformBookingStatus.CANCELLING);
        verify(provider, times(1)).cancel("SB-1");
    }

    @Test
    void aClosedWalletCancelsWithoutPostingAndAlertsAnOfflineRefund() {
        wallet.setStatus(WalletStatus.CLOSED);
        supplierRefunds("4000.00");

        PlatformBooking b = service.cancel(ID, "ck-1");

        assertThat(b.getStatus()).isEqualTo(PlatformBookingStatus.CANCELLED);
        verifyNothingPosted();
        verify(notificationService).platformAlert(contains(ID), contains("offline"));
        // the agency email must say the refund comes by bank, not to the wallet
        verify(notificationService).bookingCancelled(b, true);
    }

    @Test
    void lateTicketResultOnACancellingBookingDoesNotDebitAgain() {
        rows.get(ID).setStatus(PlatformBookingStatus.CANCELLING);

        service.applySupplierResult(ID, new BookResult("SB-1", SupplierBookingStatus.TICKETED, "PNR123", List.of(), "Ticketed", null));

        assertThat(rows.get(ID).getStatus()).isEqualTo(PlatformBookingStatus.CANCELLING);
        verify(walletService, never()).debitHeld(anyString(), any(), anyString(), anyString(), anyString());
    }

    private void cancellingAfterATimeout() {
        when(provider.cancelQuote("SB-1")).thenReturn(new CancelQuote(new BigDecimal("4000.00"), "quoted"));
        when(provider.cancel("SB-1")).thenThrow(new SupplierTimeoutException("Tripjack cancel timed out", null));
        service.cancel(ID, "ck-1");
        loginAsPlatformAdmin();
    }

    @Test
    void adminResolvesACancellingBookingAsCancelled() {
        cancellingAfterATimeout();

        PlatformBooking b = service.resolveCancel(ID, true, new BigDecimal("4000.00"), "Tripjack confirmed by email");

        assertThat(b.getStatus()).isEqualTo(PlatformBookingStatus.CANCELLED);
        verify(walletService).credit(eq(T), eq(WalletEntryType.REFUND_CREDIT), eq(new BigDecimal("4000.00")),
                eq("pb-refund:" + ID), eq(REF), eq(ID), anyString());
        verify(walletService).debitOrReceivable(T, WalletEntryType.COMMISSION_RECALL, new BigDecimal("95.00"),
                "pb-recall:" + ID, REF, ID, ReceivableReason.CANCEL_NET_NEGATIVE);
        verify(platformAuditService).record(eq(PlatformAuditAction.BOOKING_CANCEL_RESOLVE), eq(REF), eq(ID), any());
        assertThat(eventTypes()).contains("RESOLVED");
    }

    @Test
    void resolveAuditRecordsTheTypedRefundAndTheCappedOneThatWasPosted() {
        cancellingAfterATimeout();

        service.resolveCancel(ID, true, new BigDecimal("20000.00"), "Tripjack confirmed by email");

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> detail = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(platformAuditService).record(eq(PlatformAuditAction.BOOKING_CANCEL_RESOLVE), eq(REF), eq(ID), detail.capture());
        assertThat(detail.getValue()).containsEntry("requestedRefund", new BigDecimal("20000.00"))
                .containsEntry("postedRefund", new BigDecimal("4500.00")); // capped at displayed
    }

    @Test
    void adminResolvesNotCancelledBackToTicketed() {
        cancellingAfterATimeout();

        PlatformBooking b = service.resolveCancel(ID, false, null, "Tripjack says the booking is live");

        assertThat(b.getStatus()).isEqualTo(PlatformBookingStatus.TICKETED);
        verifyNothingPosted();
        verify(platformAuditService).record(eq(PlatformAuditAction.BOOKING_CANCEL_RESOLVE), eq(REF), eq(ID), any());
    }

    @Test
    void resolveCancelIsAdminOnlyValidatedAndOnlyForCancellingRows() {
        assertThatThrownBy(() -> service.resolveCancel(ID, false, null, "note")).isInstanceOf(AccessDeniedException.class);
        loginAsPlatformAdmin();
        assertThatThrownBy(() -> service.resolveCancel(ID, false, null, "note")).isInstanceOf(IllegalStateException.class); // TICKETED
        rows.get(ID).setStatus(PlatformBookingStatus.CANCELLING);
        assertThatThrownBy(() -> service.resolveCancel(ID, true, null, "note")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.resolveCancel(ID, true, new BigDecimal("-1"), "note")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.resolveCancel(ID, false, null, " ")).isInstanceOf(IllegalArgumentException.class);
        verifyNothingPosted();
        verify(platformAuditService, never()).record(any(), anyString(), anyString(), any());
    }

    @Test
    void onlyTicketedCanBeCancelled() {
        for (PlatformBookingStatus s : List.of(PlatformBookingStatus.REVIEWING, PlatformBookingStatus.HELD, PlatformBookingStatus.BOOKING,
                PlatformBookingStatus.UNKNOWN, PlatformBookingStatus.FAILED, PlatformBookingStatus.ABORTED)) {
            rows.put(ID, ticketedRow(s));
            assertThatThrownBy(() -> service.cancel(ID, "ck-1")).as(s.name()).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> service.cancelQuote(ID)).as(s.name()).isInstanceOf(IllegalStateException.class);
        }
        verify(provider, never()).cancelQuote(anyString());
        verify(provider, never()).cancel(anyString());
        verifyNothingPosted();
    }

    @Test
    void cancelTwiceWithTheSameKeyPostsOnce() {
        supplierRefunds("4000.00");

        service.cancel(ID, "ck-1");
        PlatformBooking again = service.cancel(ID, "ck-1");

        assertThat(again.getStatus()).isEqualTo(PlatformBookingStatus.CANCELLED);
        verify(provider, times(1)).cancel("SB-1");
        verify(walletService, times(1)).credit(anyString(), any(), any(), anyString(), anyString(), anyString(), anyString());
        verify(walletService, times(1)).debitOrReceivable(anyString(), any(), any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void voyraNetRecomputedWithReversal() {
        when(settings.voyraCancelFee()).thenReturn(new BigDecimal("250.00"));
        supplierRefunds("4000.00");

        PlatformBooking b = service.cancel(ID, "ck-1");

        // earned reversed (-127.50), commission recovered (+100), Voyra keeps R - refund = 250:
        // -472.50 - 127.50 + 100 + 250 = -250.00
        assertThat(b.getVoyraEarnedReversal()).isEqualByComparingTo("127.50");
        assertThat(b.getVoyraNet()).isEqualByComparingTo("-250.00");
        verify(notificationService, never()).platformAlert(anyString(), anyString()); // better than -472.50: no loss alert
    }

    @Test
    void cancellationReversesTheStatementEarningWhenOneIsInForce() {
        when(settings.voyraCancelFee()).thenReturn(new BigDecimal("250.00"));
        supplierRefunds("4000.00");
        // a statement said Voyra earned 140 (not the 127.50 estimate): net was re-derived to -460.00
        rows.put(ID, ticketedRow(PlatformBookingStatus.TICKETED).toBuilder().voyraEarnedActual(new BigDecimal("140.00"))
                .voyraEarnedSource(com.voyra.crm.enums.VoyraEarnedSource.STATEMENT).voyraNet(new BigDecimal("-460.00")).build());

        PlatformBooking b = service.cancel(ID, "ck-1");

        // -460 - 140 + 100 + 250 = -250.00: the actual is reversed, the estimate stays as stamped
        assertThat(b.getVoyraEarnedReversal()).isEqualByComparingTo("140.00");
        assertThat(b.getVoyraEarned()).isEqualByComparingTo("127.50");
        assertThat(b.getVoyraNet()).isEqualByComparingTo("-250.00");
    }

    @Test
    void lossAlertOnlyWhenTheCancelMakesVoyraNetWorse() {
        supplierRefunds("4000.00");

        PlatformBooking b = service.cancel(ID, "ck-1");

        // -472.50 - 127.50 + 100 + 0 = -500.00, worse than at ticket
        assertThat(b.getVoyraNet()).isEqualByComparingTo("-500.00");
        verify(notificationService).platformAlert(contains(ID), anyString());
    }

    @Test
    void cancelQuoteShowsRefundRecallAndNet() {
        when(settings.voyraCancelFee()).thenReturn(new BigDecimal("250.00"));
        supplierRefunds("4000.00");

        CancelPreview q = service.cancelQuote(ID);

        assertThat(q.refund()).isEqualByComparingTo("3750.00");
        assertThat(q.recall()).isEqualByComparingTo("95.00");
        assertThat(q.net()).isEqualByComparingTo("3655.00");
        assertThat(q.voyraFee()).isEqualByComparingTo("250.00");
        assertThat(q.refundToBeConfirmed()).isFalse();
        assertThat(q.cancellationPolicy()).isNull(); // flights carry no hotel policy
        assertThat(q.freeCancellationUntil()).isNull();
        verifyNothingPosted();
    }

    @Test
    void unknownQuotedRefundIsFlaggedAndCancelRefusesBeforeTheSupplier() {
        supplierRefunds(null);

        CancelPreview q = service.cancelQuote(ID);
        assertThat(q.refund()).isNull();
        assertThat(q.net()).isNull();
        assertThat(q.refundToBeConfirmed()).isTrue();

        assertThatThrownBy(() -> service.cancel(ID, "ck-1"))
                .isInstanceOfSatisfying(BookingConflictException.class, e -> assertThat(e.getCode()).isEqualTo("REFUND_UNKNOWN"));
        verify(provider, never()).cancel(anyString());
        verifyNothingPosted();
        assertThat(rows.get(ID).getStatus()).isEqualTo(PlatformBookingStatus.TICKETED);
    }

    @Test
    void aNegativeQuotedRefundIsTreatedAsUnknown() {
        supplierRefunds("-50.00");

        CancelPreview q = service.cancelQuote(ID);
        assertThat(q.refund()).isNull();
        assertThat(q.refundToBeConfirmed()).isTrue();
        assertThatThrownBy(() -> service.cancel(ID, "ck-1"))
                .isInstanceOfSatisfying(BookingConflictException.class, e -> assertThat(e.getCode()).isEqualTo("REFUND_UNKNOWN"));
        verify(provider, never()).cancel(anyString());
    }

    @Test
    void aNegativeAcceptedRefundGoesToTheManualPath() {
        when(provider.cancelQuote("SB-1")).thenReturn(new CancelQuote(new BigDecimal("4000.00"), "quoted"));
        when(provider.cancel("SB-1")).thenReturn(new CancelResult(true, new BigDecimal("-50.00"), "Cancelled"));

        PlatformBooking b = service.cancel(ID, "ck-1");

        assertThat(b.getStatus()).isEqualTo(PlatformBookingStatus.CANCELLED);
        assertThat(b.getCancelRefund()).isNull();
        verifyNothingPosted();
        verify(notificationService).platformAlert(contains(ID), contains("post refund manually"));
    }

    @Test
    void acceptedWithoutARefundCancelsAndAlertsForAManualPost() {
        when(provider.cancelQuote("SB-1")).thenReturn(new CancelQuote(new BigDecimal("4000.00"), "quoted"));
        when(provider.cancel("SB-1")).thenReturn(new CancelResult(true, null, "Cancelled"));

        PlatformBooking b = service.cancel(ID, "ck-1");

        assertThat(b.getStatus()).isEqualTo(PlatformBookingStatus.CANCELLED);
        assertThat(b.getCancelRefund()).isNull();
        verifyNothingPosted();
        verify(notificationService).platformAlert(contains(ID), contains("post refund manually"));
        assertThat(eventTypes()).contains("CANCELLED");
    }

    @Test
    void usesTheReviewIdWhenTheSupplierBookingIdIsMissing() {
        rows.get(ID).setSupplierBookingId(null);
        when(provider.cancelQuote("rv-1")).thenReturn(new CancelQuote(new BigDecimal("4000.00"), "quoted"));
        when(provider.cancel("rv-1")).thenReturn(new CancelResult(true, new BigDecimal("4000.00"), "Cancelled"));

        assertThat(service.cancel(ID, "ck-1").getStatus()).isEqualTo(PlatformBookingStatus.CANCELLED);
    }

    @Test
    void anotherAgencysBookingIsNotFound() {
        loginAs("OWNER2", "T2");

        assertThatThrownBy(() -> service.cancel(ID, "ck-1")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.cancelQuote(ID)).isInstanceOf(NotFoundException.class);
        verify(provider, never()).cancel(anyString());
    }

    @Test
    void cancelRefusesToRunInsideATransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> service.cancel(ID, "ck-1")).isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verify(provider, never()).cancel(anyString());
    }

    /** cancel for a hotel reuses the flight path but quotes and cancels with the hotel supplier. */
    @Test
    void aHotelBookingIsCancelledThroughTheHotelSupplier() {
        rows.get(ID).setProduct(PricingProduct.HOTEL);
        when(hotelProvider.cancelQuote("SB-1")).thenReturn(new CancelQuote(new BigDecimal("4000.00"), "quoted"));
        when(hotelProvider.cancel("SB-1")).thenReturn(new CancelResult(true, new BigDecimal("4000.00"), "Cancelled"));

        assertThat(service.cancelQuote(ID).refund()).isEqualByComparingTo("4000.00");
        PlatformBooking b = service.cancel(ID, "ck-1");

        assertThat(b.getStatus()).isEqualTo(PlatformBookingStatus.CANCELLED);
        verify(hotelProvider).cancel("SB-1");
        verify(provider, never()).cancelQuote(any());
        verify(provider, never()).cancel(any());
        verify(walletService).credit(eq(T), eq(WalletEntryType.REFUND_CREDIT), eq(new BigDecimal("4000.00")),
                eq("pb-refund:" + ID), eq(REF), eq(ID), contains("hotel booking"));
        verify(walletService).debitOrReceivable(T, WalletEntryType.COMMISSION_RECALL, new BigDecimal("95.00"),
                "pb-recall:" + ID, REF, ID, ReceivableReason.CANCEL_NET_NEGATIVE);
    }

    /** A HOTEL row as reviewed: the rate's policy and free-cancellation deadline are stored on the itinerary. */
    private PlatformBooking hotelRow() {
        PlatformBooking b = rows.get(ID);
        b.setProduct(PricingProduct.HOTEL);
        Map<String, Object> itinerary = new LinkedHashMap<>();
        itinerary.put("hotel", Map.of("name", "Sea Breeze Resort", "city", "Goa"));
        itinerary.put("cancellationPolicy", "Free cancellation until 2026-11-29");
        itinerary.put("freeCancellationUntil", "2026-11-29T00:00:00Z");
        b.setItinerary(itinerary);
        return b;
    }

    @Test
    void aHotelCancelQuoteShowsThePolicyAndTheFreeCancellationDeadline() {
        hotelRow();
        when(hotelProvider.cancelQuote("SB-1")).thenReturn(new CancelQuote(new BigDecimal("4000.00"), "quoted"),
                new CancelQuote(null, "unknown"));

        CancelPreview q = service.cancelQuote(ID);
        assertThat(q.cancellationPolicy()).isEqualTo("Free cancellation until 2026-11-29");
        assertThat(q.freeCancellationUntil()).isEqualTo(Instant.parse("2026-11-29T00:00:00Z"));
        assertThat(q.refund()).isEqualByComparingTo("4000.00");

        CancelPreview unknown = service.cancelQuote(ID); // the slice 3 rule: no figure, Voyra support settles it
        assertThat(unknown.refund()).isNull();
        assertThat(unknown.refundToBeConfirmed()).isTrue();
        assertThat(unknown.cancellationPolicy()).isEqualTo("Free cancellation until 2026-11-29");
        verifyNothingPosted();
    }

    /** Review Focus 2: past the free-cancellation deadline the supplier's cancel RESULT (0) is what is posted, not the earlier quote (200), and the recall still applies. */
    @Test
    void aHotelCancelledPastTheDeadlinePostsTheQuotedZeroAndStillRecalls() {
        hotelRow();
        when(hotelProvider.cancelQuote("SB-1")).thenReturn(new CancelQuote(new BigDecimal("200.00"), "quoted before the deadline passed"));
        when(hotelProvider.cancel("SB-1")).thenReturn(new CancelResult(true, BigDecimal.ZERO, "Cancelled"));

        CancelPreview q = service.cancelQuote(ID);
        assertThat(q.refund()).isEqualByComparingTo("200.00");
        assertThat(q.refundToBeConfirmed()).isFalse();

        PlatformBooking b = service.cancel(ID, "ck-1");

        assertThat(b.getStatus()).isEqualTo(PlatformBookingStatus.CANCELLED);
        assertThat(b.getCancelRefund()).isEqualByComparingTo("0");
        verify(walletService, never()).credit(anyString(), eq(WalletEntryType.REFUND_CREDIT), any(), anyString(), anyString(), anyString(), anyString());
        verify(walletService).debitOrReceivable(T, WalletEntryType.COMMISSION_RECALL, new BigDecimal("95.00"),
                "pb-recall:" + ID, REF, ID, ReceivableReason.CANCEL_NET_NEGATIVE);
    }

    /**
     * Readiness: a Tripjack amendment is only accepted by submit-amendment; the refund is final later. The booking stays
     * CANCELLING and nothing is posted until the poller sees bookingDetails say cancelled. The mock keeps settling at once.
     */
    @Test
    void anAcceptedTripjackCancelStaysCancellingAndPostsNothing() {
        flightConfig.setProvider("tripjack");
        rows.get(ID).setEnvironment(SupplierEnvironment.UAT); // booked with Tripjack
        supplierRefunds("4000.00");

        PlatformBooking b = service.cancel(ID, "ck-1");

        assertThat(b.getStatus()).isEqualTo(PlatformBookingStatus.CANCELLING);
        assertThat(rows.get(ID).getStatus()).isEqualTo(PlatformBookingStatus.CANCELLING);
        verifyNothingPosted();
        verify(notificationService, never()).bookingCancelled(any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(mirrorService, never()).sync(anyString());
        assertThat(eventTypes()).containsSubsequence("CANCEL_REQUESTED", "CANCEL_ACCEPTED").doesNotContain("CANCELLED");

        // a retry with the same key never submits again
        assertThat(service.cancel(ID, "ck-1").getStatus()).isEqualTo(PlatformBookingStatus.CANCELLING);
        verify(provider, times(1)).cancel("SB-1");
    }

    /** Final review Important 1: a live ticket is never cancelled through the mock provider; nothing is asked or posted. */
    @Test
    void aLiveRowIsNotCancelledThroughTheMockProvider() {
        rows.get(ID).setEnvironment(SupplierEnvironment.PROD); // provider is the mock
        supplierRefunds("4000.00");

        assertThatThrownBy(() -> service.cancelQuote(ID)).isInstanceOfSatisfying(BookingConflictException.class, e -> {
            assertThat(e.getCode()).isEqualTo("BOOKING_UNAVAILABLE");
            assertThat(e.getMessage()).isEqualTo("This booking can't be changed right now. Voyra has been notified.");
        });
        assertThatThrownBy(() -> service.cancel(ID, "ck-1")).isInstanceOf(BookingConflictException.class)
                .extracting("code").isEqualTo("BOOKING_UNAVAILABLE");

        org.mockito.Mockito.verifyNoInteractions(provider);
        verifyNothingPosted();
        assertThat(rows.get(ID).getStatus()).isEqualTo(PlatformBookingStatus.TICKETED);
        verify(notificationService, times(1)).platformAlert(contains(ID), anyString()); // once per row
        assertThat(eventTypes()).containsExactly("SUPPLIER_MISMATCH");
    }

    /** A row booked on the mock is not sent to Tripjack once the provider is Tripjack: the same refusal. */
    @Test
    void aMockRowIsNotCancelledThroughTripjack() {
        flightConfig.setProvider("tripjack");
        supplierRefunds("4000.00");

        assertThatThrownBy(() -> service.cancel(ID, "ck-1")).isInstanceOf(BookingConflictException.class)
                .extracting("code").isEqualTo("BOOKING_UNAVAILABLE");
        org.mockito.Mockito.verifyNoInteractions(provider);
        verifyNothingPosted();
    }

    // ---------------------------------------------------------------- coverage: cancel refusals and settlement edges

    @Test
    void aQuoteInsideATransactionOrACancelWithoutAKeyIsRefused() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> service.cancelQuote(ID)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cancelQuote must not run inside a transaction");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        assertThatThrownBy(() -> service.cancel(ID, null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("An idempotency key is required to cancel a booking");
        assertThatThrownBy(() -> service.cancel(ID, " ")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("An idempotency key is required to cancel a booking");
        verifyNothingPosted();
    }

    @Test
    void aRefusalWithoutTheSuppliersWordsRecordsTheFixedSentence() {
        when(provider.cancelQuote("SB-1")).thenReturn(new CancelQuote(new BigDecimal("4000.00"), "quoted"));
        when(provider.cancel("SB-1")).thenReturn(new CancelResult(false, null, null));

        assertThatThrownBy(() -> service.cancel(ID, "ck-1")).isInstanceOf(BookingConflictException.class);

        assertThat(savedEvents.stream().filter(ev -> ev.getType().equals("CANCEL_REFUSED")).findFirst().orElseThrow().getPayload())
                .containsEntry("message", AgencySupplierMessage.CANCEL_REFUSED);
        assertThat(rows.get(ID).getStatus()).isEqualTo(PlatformBookingStatus.TICKETED);
    }

    @Test
    void aRefusalForARowThatMovedOnLeavesItAlone() {
        when(provider.cancelQuote("SB-1")).thenReturn(new CancelQuote(new BigDecimal("4000.00"), "quoted"));
        when(provider.cancel("SB-1")).thenAnswer(i -> {
            rows.get(ID).setStatus(PlatformBookingStatus.CANCELLED); // an admin settled it meanwhile
            return new CancelResult(false, null, "late");
        });

        assertThatThrownBy(() -> service.cancel(ID, "ck-1")).isInstanceOf(BookingConflictException.class);

        assertThat(rows.get(ID).getStatus()).isEqualTo(PlatformBookingStatus.CANCELLED);
        assertThat(eventTypes()).doesNotContain("CANCEL_REFUSED");
    }

    @Test
    void aBookingWithoutTdsRecallsTheWholeCommission() {
        rows.get(ID).setTds(null);
        supplierRefunds("4000.00");

        service.cancel(ID, "ck-1");

        verify(walletService).debitOrReceivable(T, WalletEntryType.COMMISSION_RECALL, new BigDecimal("100.00"),
                "pb-recall:" + ID, REF, ID, ReceivableReason.CANCEL_NET_NEGATIVE);
    }

    @Test
    void resolveCancelNeedsANoteAndAKnownBooking() {
        loginAsPlatformAdmin();

        assertThatThrownBy(() -> service.resolveCancel(ID, false, null, null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A note is required to resolve a cancellation");
        assertThatThrownBy(() -> service.resolveCancel("NOPE", false, null, "note")).isInstanceOf(NotFoundException.class)
                .hasMessage("Booking not found");
    }

    @Test
    void aConfirmedCancelWithAnUnreadableRefundOnRecordCancelsWithoutPostingARefund() {
        rows.get(ID).setStatus(PlatformBookingStatus.CANCELLING);
        savedEvents.add(PlatformBookingEvent.builder().id("E-ACC").platformBookingId(ID).type("CANCEL_ACCEPTED")
                .payload(Map.of("refund", "UNKNOWN")).createdAt(Instant.now()).build());

        service.settleConfirmedCancel(ID);

        assertThat(rows.get(ID).getStatus()).isEqualTo(PlatformBookingStatus.CANCELLED);
        verify(walletService, never()).credit(anyString(), eq(WalletEntryType.REFUND_CREDIT), any(), anyString(), anyString(), anyString(), anyString());
        verify(notificationService, org.mockito.Mockito.atLeastOnce()).platformAlert(anyString(), anyString());
    }

    @Test
    void aConfirmedCancelWithoutAnAcceptanceOrForASettledRowPostsNothing() {
        service.settleConfirmedCancel(ID); // TICKETED: already settled elsewhere
        assertThat(rows.get(ID).getStatus()).isEqualTo(PlatformBookingStatus.TICKETED);

        rows.get(ID).setStatus(PlatformBookingStatus.CANCELLING);
        service.settleConfirmedCancel(ID); // no CANCEL_ACCEPTED event: no refund on record
        verifyNothingPosted();

        assertThatThrownBy(() -> service.settleConfirmedCancel("NOPE")).isInstanceOf(NotFoundException.class)
                .hasMessage("Booking not found");
    }

    @Test
    void aHotelQuoteWithAnUnreadableDeadlineShowsNone() {
        hotelRow();
        rows.get(ID).setItinerary(new LinkedHashMap<>(Map.of("cancellationPolicy", "Refundable", "freeCancellationUntil", "soon")));
        when(hotelProvider.cancelQuote("SB-1")).thenReturn(new CancelQuote(new BigDecimal("4000.00"), "quoted"));

        CancelPreview q = service.cancelQuote(ID);

        assertThat(q.cancellationPolicy()).isEqualTo("Refundable");
        assertThat(q.freeCancellationUntil()).isNull();
    }

    @Test
    void aTripjackCancelTheSupplierAcceptsStaysCancellingUntilConfirmed() {
        flightConfig.setProvider("tripjack");
        rows.get(ID).setEnvironment(SupplierEnvironment.UAT);
        supplierRefunds("4000.00");

        PlatformBooking b = service.cancel(ID, "ck-1");

        assertThat(b.getStatus()).isEqualTo(PlatformBookingStatus.CANCELLING);
        verifyNothingPosted();
        assertThat(eventTypes()).contains("CANCEL_ACCEPTED");
    }

    @Test
    void anAcceptedCancelForARowAnAdminPutBackMeanwhileIsNotSettled() {
        when(provider.cancelQuote("SB-1")).thenReturn(new CancelQuote(new BigDecimal("4000.00"), "quoted"));
        when(provider.cancel("SB-1")).thenAnswer(i -> {
            rows.get(ID).setStatus(PlatformBookingStatus.TICKETED); // resolved "not cancelled" while the call ran
            return new CancelResult(true, new BigDecimal("4000.00"), "Cancelled");
        });

        PlatformBooking b = service.cancel(ID, "ck-1");

        assertThat(b.getStatus()).isEqualTo(PlatformBookingStatus.TICKETED);
        verifyNothingPosted();
        verify(mirrorService, never()).sync(anyString());
    }
}
