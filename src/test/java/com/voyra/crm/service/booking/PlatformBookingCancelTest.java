package com.voyra.crm.service.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.dto.FlightReviewRequest;
import com.voyra.crm.entity.AgencyPricing;
import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.entity.PlatformBookingEvent;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.entity.WalletEntry;
import com.voyra.crm.enums.PlatformBookingStatus;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.enums.ReceivableReason;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.TenantStatus;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.enums.WalletEntryType;
import com.voyra.crm.exception.BookingConflictException;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.exception.ReceivableDueException;
import com.voyra.crm.exception.SupplierException;
import com.voyra.crm.models.booking.CancelQuote;
import com.voyra.crm.models.booking.CancelResult;
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
    @Mock private AgencyPriceQuoter quoter;
    @Mock private AgencyPricingService pricingService;
    @Mock private WalletService walletService;
    @Mock private WalletRepository walletRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private PlatformSupplierCredentialRepository credentialRepository;
    @Mock private NotificationService notificationService;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private WalletEntryRepository walletEntryRepository;
    @Mock private PlatformAuditService platformAuditService;
    @Mock private PlatformSettingService settings;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final PaxCodec paxCodec = new PaxCodec(objectMapper, KEY);
    private final Map<String, PlatformBooking> rows = new HashMap<>();
    private final List<PlatformBookingEvent> savedEvents = new ArrayList<>();
    private PlatformBookingService service;

    @BeforeEach
    void setUp() {
        service = new PlatformBookingService(bookingRepository, provider, new FlightBookingProviderConfig(), quoter, pricingService,
                walletService, walletRepository, tenantRepository, agentRepository, credentialRepository, notificationService,
                new PlatformBookingEventRecorder(eventRepository, paxCodec), paxCodec, objectMapper, transactionManager,
                walletEntryRepository, platformAuditService, settings);
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
        assertThat(eventTypes()).containsSubsequence("CANCEL_REQUESTED", "CANCELLED");
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
    void recallShortfallOpensAReceivableAndBlocksBooking() {
        when(settings.voyraCancelFee()).thenReturn(new BigDecimal("250.00"));
        supplierRefunds("100.00"); // below the fee: refund 0, net = -95

        PlatformBooking b = service.cancel(ID, "ck-1");

        verify(walletService, never()).credit(anyString(), any(), any(), anyString(), anyString(), anyString(), anyString());
        verify(walletService).debitOrReceivable(T, WalletEntryType.COMMISSION_RECALL, new BigDecimal("95.00"),
                "pb-recall:" + ID, REF, ID, ReceivableReason.CANCEL_NET_NEGATIVE);
        assertThat(b.getStatus()).isEqualTo(PlatformBookingStatus.CANCELLED);
        assertThat(b.getCancelRefund()).isEqualByComparingTo("0");

        // the wallet opened a DUE receivable for what available did not cover: the next booking is refused
        when(walletService.hasDueReceivable(T)).thenReturn(true);
        when(pricingService.requireInForce(T, PricingProduct.FLIGHT)).thenReturn(AgencyPricing.builder().id("P1").build());
        when(tenantRepository.findById(T)).thenReturn(Optional.of(Tenant.builder().id(T).status(TenantStatus.ACTIVE).bookingBlocked(false).build()));
        FlightReviewRequest next = new FlightReviewRequest();
        next.setOfferId("offer-2");
        next.setAdults(1);
        assertThatThrownBy(() -> service.review(next)).isInstanceOf(ReceivableDueException.class);
        verify(provider, never()).review(anyString(), any(Integer.class), any(Integer.class), any(Integer.class));
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
                    assertThat(e.getMessage()).isEqualTo("Ticket already flown");
                });

        verifyNothingPosted();
        assertThat(rows.get(ID).getStatus()).isEqualTo(PlatformBookingStatus.TICKETED);
        assertThat(rows.get(ID).getCancelledAt()).isNull();
        assertThat(eventTypes()).contains("CANCEL_REFUSED");
    }

    @Test
    void supplierErrorLeavesTheBookingTicketedAndCanBeRetried() {
        when(provider.cancelQuote("SB-1")).thenReturn(new CancelQuote(new BigDecimal("4000.00"), "quoted"));
        when(provider.cancel("SB-1")).thenThrow(new SupplierException("Tripjack cancel call failed (HTTP 500)", null))
                .thenReturn(new CancelResult(true, new BigDecimal("4000.00"), "Cancelled"));

        assertThatThrownBy(() -> service.cancel(ID, "ck-1")).isInstanceOf(SupplierException.class);
        verifyNothingPosted();
        assertThat(rows.get(ID).getStatus()).isEqualTo(PlatformBookingStatus.TICKETED);

        assertThat(service.cancel(ID, "ck-1").getStatus()).isEqualTo(PlatformBookingStatus.CANCELLED);
        verify(walletService, times(1)).credit(anyString(), eq(WalletEntryType.REFUND_CREDIT), any(), anyString(), anyString(), anyString(), anyString());
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
}
