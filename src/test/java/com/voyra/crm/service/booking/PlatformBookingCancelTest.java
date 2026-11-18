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
import com.voyra.crm.enums.UserType;
import com.voyra.crm.enums.WalletEntryType;
import com.voyra.crm.enums.WalletStatus;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.models.booking.BookResult;
import com.voyra.crm.models.booking.SupplierBookingStatus;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.PlatformBookingEventRepository;
import com.voyra.crm.repository.PlatformBookingRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.repository.WalletEntryRepository;
import com.voyra.crm.repository.WalletRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.service.AgencyPricingService;
import com.voyra.crm.service.NotificationService;
import com.voyra.crm.service.PlatformAuditService;
import com.voyra.crm.service.PlatformSettingService;
import com.voyra.crm.service.PlatformSupplierCredentialService;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Settling a whole-booking cancellation already CANCELLING before cancellation requests (spec 7.5): the poller's
 * {@code settleConfirmedCancel} and the admin's {@code resolveCancel}. The ticketed row: gross 5000, displayed 4500, Voyra earned 127.50,
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
    @Mock private PlatformSupplierCredentialService suppliers;
    @Mock private FlightBookingProvider tripjack;
    @Mock private NotificationService notificationService;
    @Mock private PlatformBookingMirrorService mirrorService;
    @Mock private com.voyra.crm.service.EntitlementService entitlementService;
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

    private SupplierRegistry registry() {
        return BookingSuppliers.registry(suppliers, provider, tripjack, hotelProvider, flightConfig, hotelConfig);
    }

    @BeforeEach
    void setUp() {
        service = new PlatformBookingService(bookingRepository, registry(), quoter, pricingService,
                walletService, walletRepository, tenantRepository, agentRepository, notificationService,
                new PlatformBookingEventRecorder(eventRepository, paxCodec), paxCodec, objectMapper, transactionManager,
                walletEntryRepository, platformAuditService, settings, mirrorService, entitlementService);
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
                .credentialId("s1").environment(com.voyra.crm.enums.SupplierEnvironment.MOCK).supplierReviewId("rv-1").supplierBookingId("SB-1").pnr("PNR123")
                .gross(new BigDecimal("5000.00")).base(new BigDecimal("4250.00")).displayed(new BigDecimal("4500.00"))
                .agencyCommission(new BigDecimal("100.00")).tds(new BigDecimal("5.00"))
                .holdEntryId("H1").debitEntryId("D1")
                .pricingSnapshot(new LinkedHashMap<>(Map.of("pricingId", "P1"))).createdAt(Instant.now()).build();
    }

    /** The row as an agency cancel from before this release left it: CANCELLING, with the supplier's acceptance on record. */
    private PlatformBooking accepted(String refund) {
        PlatformBooking b = rows.get(ID);
        b.setStatus(PlatformBookingStatus.CANCELLING);
        savedEvents.add(PlatformBookingEvent.builder().id("E-ACC").platformBookingId(ID).type("CANCEL_ACCEPTED")
                .payload(Map.of("refund", refund, "message", "Cancelled")).createdAt(Instant.now()).build());
        return b;
    }

    private List<String> eventTypes() {
        return savedEvents.stream().map(PlatformBookingEvent::getType).toList();
    }

    private void verifyNothingPosted() {
        verify(walletService, never()).credit(anyString(), any(), any(), anyString(), anyString(), anyString(), anyString());
        verify(walletService, never()).debitOrReceivable(anyString(), any(), any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void aConfirmedCancelPostsRefundThenRecall() {
        accepted("4000.00");

        service.settleConfirmedCancel(ID);

        PlatformBooking b = rows.get(ID);
        InOrder order = inOrder(walletService);
        order.verify(walletService).credit(eq(T), eq(WalletEntryType.REFUND_CREDIT), eq(new BigDecimal("4000.00")),
                eq("pb-refund:" + ID), eq(REF), eq(ID), contains("PNR123"));
        order.verify(walletService).debitOrReceivable(T, WalletEntryType.COMMISSION_RECALL, new BigDecimal("95.00"),
                "pb-recall:" + ID, REF, ID, ReceivableReason.CANCEL_NET_NEGATIVE);
        assertThat(b.getStatus()).isEqualTo(PlatformBookingStatus.CANCELLED);
        assertThat(b.getCancelRefund()).isEqualByComparingTo("4000.00");
        assertThat(b.getCancelledAt()).isNotNull();
        assertThat(b.getSupplierMessage()).isEqualTo("Cancelled");
        verify(notificationService).bookingCancelled(b, false);
        assertThat(eventTypes()).containsSubsequence("CANCEL_ACCEPTED", "CANCELLED");
    }

    /** A cancelled booking whose CRM mirror was written goes back to PENDING; the mirror cancel runs after the refund committed. */
    @Test
    void aConfirmedCancelSchedulesTheMirrorCancelAfterTheRefund() {
        accepted("4000.00");
        rows.get(ID).setClientId("C1");
        rows.get(ID).setMirrorStatus(com.voyra.crm.enums.MirrorStatus.DONE);

        service.settleConfirmedCancel(ID);

        assertThat(rows.get(ID).getMirrorStatus()).isEqualTo(com.voyra.crm.enums.MirrorStatus.PENDING);
        InOrder order = inOrder(walletService, mirrorService);
        order.verify(walletService).credit(eq(T), eq(WalletEntryType.REFUND_CREDIT), any(), anyString(), anyString(), anyString(), anyString());
        order.verify(mirrorService).sync(ID);
    }

    /** A mirror that had failed (or never ran) is queued again too, with a MIRROR_QUEUED event that resets the repair cap. */
    @Test
    void aCancelQueuesTheMirrorWhateverItsStatus() {
        accepted("4000.00");
        rows.get(ID).setClientId("C1");
        rows.get(ID).setMirrorStatus(com.voyra.crm.enums.MirrorStatus.FAILED);

        service.settleConfirmedCancel(ID);

        assertThat(rows.get(ID).getMirrorStatus()).isEqualTo(com.voyra.crm.enums.MirrorStatus.PENDING);
        assertThat(savedEvents).filteredOn(e -> e.getType().equals("MIRROR_QUEUED"))
                .singleElement().satisfies(e -> assertThat(e.getPayload()).containsEntry("status", "CANCELLED"));
        verify(mirrorService).sync(ID);
    }

    @Test
    void refundIsNetOfTheVoyraFeeAndCappedAtDisplayed() {
        when(settings.voyraCancelFee()).thenReturn(new BigDecimal("250.00"));
        accepted("6000.00");

        service.settleConfirmedCancel(ID);

        // 6000 - 250 = 5750, capped at the 4500 the agency paid
        verify(walletService).credit(eq(T), eq(WalletEntryType.REFUND_CREDIT), eq(new BigDecimal("4500.00")),
                eq("pb-refund:" + ID), eq(REF), eq(ID), anyString());
        assertThat(rows.get(ID).getCancelRefund()).isEqualByComparingTo("4500.00");
        verify(notificationService, never()).platformAlert(anyString(), anyString()); // no "Loss-making" alert
    }

    @Test
    void zeroRefundStillRecallsUnderTheCancelNetNegativeReason() {
        when(settings.voyraCancelFee()).thenReturn(new BigDecimal("250.00"));
        accepted("100.00"); // below the fee: refund 0, net = -95

        service.settleConfirmedCancel(ID);

        // no zero credit; the recall goes to debitOrReceivable, which opens the receivable when available falls short
        verify(walletService, never()).credit(anyString(), any(), any(), anyString(), anyString(), anyString(), anyString());
        verify(walletService).debitOrReceivable(T, WalletEntryType.COMMISSION_RECALL, new BigDecimal("95.00"),
                "pb-recall:" + ID, REF, ID, ReceivableReason.CANCEL_NET_NEGATIVE);
        assertThat(rows.get(ID).getStatus()).isEqualTo(PlatformBookingStatus.CANCELLED);
        assertThat(rows.get(ID).getCancelRefund()).isEqualByComparingTo("0");
    }

    @Test
    void noCommissionMeansNoRecall() {
        rows.get(ID).setAgencyCommission(BigDecimal.ZERO);
        rows.get(ID).setTds(BigDecimal.ZERO);
        accepted("4000.00");

        service.settleConfirmedCancel(ID);

        verify(walletService).credit(eq(T), eq(WalletEntryType.REFUND_CREDIT), eq(new BigDecimal("4000.00")),
                anyString(), anyString(), anyString(), anyString());
        verify(walletService, never()).debitOrReceivable(anyString(), any(), any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void aBookingWithoutTdsRecallsTheWholeCommission() {
        rows.get(ID).setTds(null);
        accepted("4000.00");

        service.settleConfirmedCancel(ID);

        verify(walletService).debitOrReceivable(T, WalletEntryType.COMMISSION_RECALL, new BigDecimal("100.00"),
                "pb-recall:" + ID, REF, ID, ReceivableReason.CANCEL_NET_NEGATIVE);
    }

    @Test
    void aNegativeAcceptedRefundGoesToTheManualPath() {
        accepted("-50.00");

        service.settleConfirmedCancel(ID);

        assertThat(rows.get(ID).getStatus()).isEqualTo(PlatformBookingStatus.CANCELLED);
        assertThat(rows.get(ID).getCancelRefund()).isNull();
        verifyNothingPosted();
        verify(notificationService).platformAlert(contains(ID), contains("post refund manually"));
    }

    @Test
    void aConfirmedCancelWithAnUnknownRefundCancelsAndAlertsForAManualPost() {
        accepted("UNKNOWN");

        service.settleConfirmedCancel(ID);

        assertThat(rows.get(ID).getStatus()).isEqualTo(PlatformBookingStatus.CANCELLED);
        assertThat(rows.get(ID).getCancelRefund()).isNull();
        verifyNothingPosted();
        verify(notificationService).platformAlert(contains(ID), contains("post refund manually"));
        assertThat(eventTypes()).contains("CANCELLED");
    }

    @Test
    void aConfirmedCancelWithoutAnAcceptanceOrForASettledRowPostsNothing() {
        service.settleConfirmedCancel(ID); // TICKETED: already settled elsewhere
        assertThat(rows.get(ID).getStatus()).isEqualTo(PlatformBookingStatus.TICKETED);

        rows.get(ID).setStatus(PlatformBookingStatus.CANCELLING);
        service.settleConfirmedCancel(ID); // no CANCEL_ACCEPTED event: no refund on record
        verifyNothingPosted();
        assertThat(rows.get(ID).getSupplierMessage()).isNull();

        assertThatThrownBy(() -> service.settleConfirmedCancel("NOPE")).isInstanceOf(NotFoundException.class)
                .hasMessage("Booking not found");
    }

    @Test
    void aConfirmedCancelOnAClosedWalletPostsNothingAndAlertsAnOfflineRefund() {
        wallet.setStatus(WalletStatus.CLOSED);
        accepted("4000.00");

        service.settleConfirmedCancel(ID);

        PlatformBooking b = rows.get(ID);
        assertThat(b.getStatus()).isEqualTo(PlatformBookingStatus.CANCELLED);
        verifyNothingPosted();
        verify(notificationService).platformAlert(contains(ID), contains("offline"));
        // the agency email must say the refund comes by bank, not to the wallet
        verify(notificationService).bookingCancelled(b, true);
        assertThat(savedEvents).filteredOn(e -> e.getType().equals("CANCELLED"))
                .singleElement().satisfies(e -> assertThat(e.getPayload()).containsEntry("offline", true));
    }

    /** A hotel's refund is posted as a hotel booking's, with the same keys and recall. */
    @Test
    void aConfirmedHotelCancelPostsAsAHotelBooking() {
        rows.get(ID).setProduct(PricingProduct.HOTEL);
        accepted("4000.00");

        service.settleConfirmedCancel(ID);

        verify(walletService).credit(eq(T), eq(WalletEntryType.REFUND_CREDIT), eq(new BigDecimal("4000.00")),
                eq("pb-refund:" + ID), eq(REF), eq(ID), contains("hotel booking"));
        verify(walletService).debitOrReceivable(T, WalletEntryType.COMMISSION_RECALL, new BigDecimal("95.00"),
                "pb-recall:" + ID, REF, ID, ReceivableReason.CANCEL_NET_NEGATIVE);
    }

    /** Past the free-cancellation deadline the supplier accepted with 0: nothing is credited, and the recall still applies. */
    @Test
    void aHotelCancelledPastTheDeadlinePostsZeroAndStillRecalls() {
        rows.get(ID).setProduct(PricingProduct.HOTEL);
        accepted("0");

        service.settleConfirmedCancel(ID);

        assertThat(rows.get(ID).getStatus()).isEqualTo(PlatformBookingStatus.CANCELLED);
        assertThat(rows.get(ID).getCancelRefund()).isEqualByComparingTo("0");
        verify(walletService, never()).credit(anyString(), eq(WalletEntryType.REFUND_CREDIT), any(), anyString(), anyString(), anyString(), anyString());
        verify(walletService).debitOrReceivable(T, WalletEntryType.COMMISSION_RECALL, new BigDecimal("95.00"),
                "pb-recall:" + ID, REF, ID, ReceivableReason.CANCEL_NET_NEGATIVE);
    }

    @Test
    void lateTicketResultOnACancellingBookingDoesNotDebitAgain() {
        rows.get(ID).setStatus(PlatformBookingStatus.CANCELLING);

        service.applySupplierResult(ID, new BookResult("SB-1", SupplierBookingStatus.TICKETED, "PNR123", List.of(), "Ticketed", null));

        assertThat(rows.get(ID).getStatus()).isEqualTo(PlatformBookingStatus.CANCELLING);
        verify(walletService, never()).debitHeld(anyString(), any(), anyString(), anyString(), anyString());
    }

    /** A row an agency cancel left CANCELLING with no supplier answer, as the platform admin sees it. */
    private void cancellingWithoutAnAnswer() {
        rows.get(ID).setStatus(PlatformBookingStatus.CANCELLING);
        loginAsPlatformAdmin();
    }

    @Test
    void adminResolvesACancellingBookingAsCancelled() {
        cancellingWithoutAnAnswer();

        PlatformBooking b = service.resolveCancel(ID, true, new BigDecimal("4000.00"), "Tripjack confirmed by email");

        assertThat(b.getStatus()).isEqualTo(PlatformBookingStatus.CANCELLED);
        assertThat(b.getSupplierMessage()).isEqualTo("Resolved by Voyra");
        verify(walletService).credit(eq(T), eq(WalletEntryType.REFUND_CREDIT), eq(new BigDecimal("4000.00")),
                eq("pb-refund:" + ID), eq(REF), eq(ID), anyString());
        verify(walletService).debitOrReceivable(T, WalletEntryType.COMMISSION_RECALL, new BigDecimal("95.00"),
                "pb-recall:" + ID, REF, ID, ReceivableReason.CANCEL_NET_NEGATIVE);
        verify(platformAuditService).record(eq(PlatformAuditAction.BOOKING_CANCEL_RESOLVE), eq(REF), eq(ID), any());
        verify(mirrorService).sync(ID);
        assertThat(eventTypes()).contains("RESOLVED");
    }

    @Test
    void resolveAuditRecordsTheTypedRefundAndTheCappedOneThatWasPosted() {
        cancellingWithoutAnAnswer();

        service.resolveCancel(ID, true, new BigDecimal("20000.00"), "Tripjack confirmed by email");

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> detail = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(platformAuditService).record(eq(PlatformAuditAction.BOOKING_CANCEL_RESOLVE), eq(REF), eq(ID), detail.capture());
        assertThat(detail.getValue()).containsEntry("requestedRefund", new BigDecimal("20000.00"))
                .containsEntry("postedRefund", new BigDecimal("4500.00")); // capped at displayed
    }

    @Test
    void adminResolvesNotCancelledBackToTicketed() {
        cancellingWithoutAnAnswer();

        PlatformBooking b = service.resolveCancel(ID, false, null, "Tripjack says the booking is live");

        assertThat(b.getStatus()).isEqualTo(PlatformBookingStatus.TICKETED);
        verifyNothingPosted();
        verify(mirrorService, never()).sync(anyString());
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> detail = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(platformAuditService).record(eq(PlatformAuditAction.BOOKING_CANCEL_RESOLVE), eq(REF), eq(ID), detail.capture());
        assertThat(detail.getValue()).containsEntry("requestedRefund", "NONE").containsEntry("postedRefund", "NONE");
    }

    @Test
    void adminResolvesACancelOnAClosedWalletOffline() {
        wallet.setStatus(WalletStatus.CLOSED);
        cancellingWithoutAnAnswer();

        PlatformBooking b = service.resolveCancel(ID, true, new BigDecimal("4000.00"), "Tripjack confirmed by email");

        assertThat(b.getStatus()).isEqualTo(PlatformBookingStatus.CANCELLED);
        verifyNothingPosted();
        verify(notificationService).platformAlert(contains(ID), contains("offline"));
        verify(notificationService).bookingCancelled(b, true);
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
    void resolveCancelNeedsANoteAndAKnownBooking() {
        loginAsPlatformAdmin();

        assertThatThrownBy(() -> service.resolveCancel(ID, false, null, null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A note is required to resolve a cancellation");
        assertThatThrownBy(() -> service.resolveCancel("NOPE", false, null, "note")).isInstanceOf(NotFoundException.class)
                .hasMessage("Booking not found");
    }

    /** A row whose supplier row is now in another environment is never asked; Voyra is alerted once (seat map is the agency path left). */
    @Test
    void aRowWhoseSupplierNoLongerMatchesIsNotServed() {
        PlatformBooking b = rows.get(ID);
        b.setStatus(PlatformBookingStatus.REVIEWING);
        b.setEnvironment(com.voyra.crm.enums.SupplierEnvironment.PROD); // its row s1 is MOCK

        assertThatThrownBy(() -> service.seatMap(ID)).isInstanceOfSatisfying(com.voyra.crm.exception.BookingConflictException.class, e -> {
            assertThat(e.getCode()).isEqualTo("BOOKING_UNAVAILABLE");
            assertThat(e.getMessage()).isEqualTo("This booking can't be changed right now. Voyra has been notified.");
        });
        assertThatThrownBy(() -> service.seatMap(ID)).isInstanceOf(com.voyra.crm.exception.BookingConflictException.class);

        verify(provider, never()).seatMap(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
        verify(notificationService, org.mockito.Mockito.times(1)).platformAlert(contains(ID), anyString()); // once per row
        assertThat(eventTypes()).containsExactly("SUPPLIER_MISMATCH");
        assertThat(savedEvents.get(0).getPayload()).containsEntry("environment", "PROD").containsEntry("status", "REVIEWING")
                .containsEntry("credentialId", "s1");
    }
}
