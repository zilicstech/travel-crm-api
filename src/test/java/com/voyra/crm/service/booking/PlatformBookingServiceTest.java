package com.voyra.crm.service.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.dto.FlightReviewRequest;
import com.voyra.crm.dto.HotelReviewRequest;
import com.voyra.crm.dto.HotelRoomRequest;
import com.voyra.crm.dto.PaxInput;
import com.voyra.crm.dto.PlatformBookingConfirmRequest;
import com.voyra.crm.dto.PlatformBookingReviewResponse;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.PricingRule;
import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.entity.PlatformBookingEvent;
import com.voyra.crm.entity.PlatformSupplierCredential;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.entity.Wallet;
import com.voyra.crm.entity.WalletEntry;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.PlatformBookingStatus;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.enums.ReceivableReason;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.enums.TenantStatus;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.enums.VoyraEarnedSource;
import com.voyra.crm.enums.WalletEntryType;
import com.voyra.crm.exception.BookingConflictException;
import com.voyra.crm.exception.InsufficientBalanceException;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.exception.PricingNotConfiguredException;
import com.voyra.crm.exception.ReceivableDueException;
import com.voyra.crm.exception.SupplierException;
import com.voyra.crm.exception.SupplierTimeoutException;
import com.voyra.crm.models.booking.BookRequest;
import com.voyra.crm.models.booking.BookResult;
import com.voyra.crm.models.booking.FareReview;
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
import org.mockito.ArgumentCaptor;
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
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.calls;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlatformBookingServiceTest {

    private static final String T = "T1";
    private static final String KEY = "dGVzdC1vbmx5LWFlcy1rZXktMzItYnl0ZXMtbG9uZyE=";
    private static final String REF = "PLATFORM_BOOKING";

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

    private final FlightBookingProviderConfig config = new FlightBookingProviderConfig();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final PaxCodec paxCodec = new PaxCodec(objectMapper, KEY);
    private final Map<String, PlatformBooking> rows = new HashMap<>();
    private final List<PlatformBookingEvent> savedEvents = new ArrayList<>();
    /** Wallet entries the mocked WalletService "posted", by idempotency key. */
    private final Map<String, WalletEntry> walletEntries = new HashMap<>();
    private PlatformBookingService service;
    private Wallet wallet;
    private Tenant tenant;

    /** gross 5000, base 4250, 10% off gross, 2.35% commission (100.00), 5% TDS. */
    private static PriceQuote quote(String pricingId, String displayed, String commission, String tds) {
        BigDecimal c = new BigDecimal(commission);
        BigDecimal t = new BigDecimal(tds);
        BigDecimal d = new BigDecimal(displayed);
        BigDecimal earned = new BigDecimal("127.50");
        return new PriceQuote(d, c.subtract(t), earned, c, t,
                earned.subtract(new BigDecimal("5000.00").subtract(d)).subtract(c), pricingId, BigDecimal.TEN, new BigDecimal("2.35"));
    }

    private final HotelBookingProviderConfig hotelConfig = new HotelBookingProviderConfig();

    private BookingProviderRegistry registry() {
        return new BookingProviderRegistry(provider, hotelProvider, config, hotelConfig);
    }

    @BeforeEach
    void setUp() {
        service = new PlatformBookingService(bookingRepository, registry(), quoter, pricingService, walletService,
                walletRepository, tenantRepository, agentRepository, credentialRepository, notificationService,
                new PlatformBookingEventRecorder(eventRepository, paxCodec), paxCodec, objectMapper, transactionManager,
                walletEntryRepository, platformAuditService, settings, mirrorService);
        wallet = Wallet.builder().id("W1").tenantId(T).balance(new BigDecimal("10000.00")).held(BigDecimal.ZERO)
                .isTest(true).build();
        tenant = Tenant.builder().id(T).status(TenantStatus.ACTIVE).bookingBlocked(false).build();

        lenient().when(bookingRepository.save(any())).thenAnswer(i -> {
            PlatformBooking b = i.getArgument(0);
            rows.put(b.getId(), b);
            return b;
        });
        lenient().when(bookingRepository.existsById(anyString())).thenAnswer(i -> rows.containsKey(i.<String>getArgument(0)));
        lenient().when(bookingRepository.findByIdForUpdate(anyString())).thenAnswer(i -> Optional.ofNullable(rows.get(i.<String>getArgument(0))));
        lenient().when(bookingRepository.findById(anyString())).thenAnswer(i -> Optional.ofNullable(rows.get(i.<String>getArgument(0))));
        lenient().when(eventRepository.save(any())).thenAnswer(i -> {
            savedEvents.add(i.getArgument(0));
            return i.getArgument(0);
        });
        lenient().when(walletRepository.findByTenantId(T)).thenAnswer(i -> Optional.of(wallet));
        lenient().when(tenantRepository.findById(T)).thenAnswer(i -> Optional.of(tenant));
        lenient().when(pricingService.requireInForce(T, PricingProduct.FLIGHT)).thenReturn(rules());
        lenient().when(provider.review(any(FlightReviewRequest.class)))
                .thenReturn(new FareReview("rv-1", new BigDecimal("5000.00"), new BigDecimal("4250.00"), "INR",
                        "Refundable", Instant.now().plus(15, ChronoUnit.MINUTES), null));
        lenient().when(quoter.quote(eq(T), eq(PricingProduct.FLIGHT), any(), any())).thenReturn(quote("P1", "4500.00", "100.00", "0.00"));
        lenient().when(walletService.hold(eq(T), any(), anyString(), anyString(), anyString())).thenAnswer(i -> {
            WalletEntry h = WalletEntry.builder().id("H1").tenantId(T).type(WalletEntryType.HOLD).idempotencyKey(i.getArgument(2)).build();
            walletEntries.put(h.getIdempotencyKey(), h);
            return h;
        });
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
        lenient().when(walletService.debitOrReceivable(eq(T), eq(WalletEntryType.BOOKING_DEBIT), any(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(WalletEntry.builder().id("D2").tenantId(T).build());
        lenient().when(walletService.debitHeld(anyString(), any(), anyString(), anyString(), anyString()))
                .thenReturn(WalletEntry.builder().id("D1").tenantId(T).build());
        lenient().when(provider.book(any())).thenReturn(ticketed(null));
        loginAs(UserType.AGENCY_OWNER, "OWNER1");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void loginAs(UserType type, String userId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal(userId, userId + "@agency.test", type, T), null));
    }

    private static BookResult ticketed(BigDecimal amount) {
        return new BookResult("SB-1", SupplierBookingStatus.TICKETED, "PNR123", List.of("TKT-1", "TKT-2", "TKT-3"), "Ticketed", amount);
    }

    private static FlightReviewRequest reviewRequest() {
        FlightReviewRequest r = new FlightReviewRequest();
        r.setOfferId("offer-1");
        r.setAdults(2);
        r.setChildren(1);
        return r;
    }

    private static PlatformBookingConfirmRequest confirmRequest(String key, String accepted) {
        PlatformBookingConfirmRequest r = new PlatformBookingConfirmRequest();
        r.setIdempotencyKey(key);
        r.setAcceptedDisplayedFare(new BigDecimal(accepted));
        r.setContactEmail("desk@agency.test");
        r.setContactPhone("9876543210");
        r.setPassengers(List.of(
                PaxInput.builder().type("ADULT").title("Mr").firstName("Rahul").lastName("Sharma").passportNo("N1234567").build(),
                PaxInput.builder().type("ADULT").title("Ms").firstName("Priya").lastName("Sharma").build(),
                PaxInput.builder().type("CHILD").title("Mstr").firstName("Aarav").lastName("Sharma").dob(LocalDate.of(2019, 5, 1)).build()));
        return r;
    }

    private String reviewed() {
        return service.review(reviewRequest()).getBookingId();
    }

    @Test
    void reviewStampsPricingSnapshot() {
        PlatformBookingReviewResponse res = service.review(reviewRequest());

        PlatformBooking b = rows.get(res.getBookingId());
        assertThat(b.getStatus()).isEqualTo(PlatformBookingStatus.REVIEWING);
        assertThat(b.getTenantId()).isEqualTo(T);
        assertThat(b.getProduct()).isEqualTo(PricingProduct.FLIGHT);
        assertThat(b.getEnvironment()).isEqualTo(SupplierEnvironment.MOCK);
        assertThat(b.getCredentialId()).isNull();
        assertThat(b.getSupplierReviewId()).isEqualTo("rv-1");
        assertThat(b.getGross()).isEqualByComparingTo("5000.00");
        assertThat(b.getBase()).isEqualByComparingTo("4250.00");
        assertThat(b.getDisplayed()).isEqualByComparingTo("4500.00");
        assertThat(b.getDiscountPct()).isEqualByComparingTo("10");
        assertThat(b.getVoyraEarned()).isEqualByComparingTo("127.50");
        assertThat(b.getVoyraEarnedSource()).isEqualTo(VoyraEarnedSource.ESTIMATE);
        assertThat(b.getAgencyCommission()).isEqualByComparingTo("100.00");
        assertThat(b.getVoyraNet()).isEqualByComparingTo("-472.50");
        assertThat(b.getPricingSnapshot()).containsEntry("pricingId", "P1");
        assertThat(b.getPaxCount()).isEqualTo(3);
        assertThat(b.getBookedBy()).isEqualTo("OWNER1");
        assertThat(b.getReviewExpiresAt()).isAfter(Instant.now());
        assertThat(res.getDisplayedFare()).isEqualByComparingTo("4500.00");
        assertThat(res.getAgencyEarns()).isEqualByComparingTo("100.00");
        assertThat(res.getWalletAvailable()).isEqualByComparingTo("10000.00");
        assertThat(res.isPriceChanged()).isFalse();
        assertThat(res.getFareRules()).isEqualTo("Refundable");
        verify(provider).review(argThat((FlightReviewRequest r) -> r.getOfferId().equals("offer-1") && r.getAdults() == 2 && r.getChildren() == 1));
    }

    @Test
    void reviewFlagsAPriceChangedSinceSearch() {
        FlightReviewRequest req = reviewRequest();
        req.setSearchDisplayedFare(new BigDecimal("4400.00"));

        PlatformBookingReviewResponse res = service.review(req);

        assertThat(res.isPriceChanged()).isTrue();
        assertThat(res.getPreviousDisplayedFare()).isEqualByComparingTo("4400.00");
    }

    @Test
    void reviewStampsTheActiveCredentialEnvironment() {
        config.setProvider("tripjack");
        when(credentialRepository.findByProviderAndIsActiveTrue(SupplierProvider.TRIPJACK)).thenReturn(Optional.of(
                PlatformSupplierCredential.builder().id("CRED1").environment(SupplierEnvironment.UAT).build()));

        PlatformBooking b = rows.get(reviewed());

        assertThat(b.getEnvironment()).isEqualTo(SupplierEnvironment.UAT);
        assertThat(b.getCredentialId()).isEqualTo("CRED1");
    }

    /** The discount and commission are worked off the base fare: without it there is no price to show, so 409, not a 500. */
    @Test
    void reviewWithAMissingBaseFareIsAConflictAndSavesNothing() {
        when(provider.review(any(FlightReviewRequest.class)))
                .thenReturn(new FareReview("rv-1", new BigDecimal("5000.00"), null, "INR", null, null, null));

        assertThatThrownBy(() -> service.review(reviewRequest()))
                .isInstanceOfSatisfying(BookingConflictException.class, e -> assertThat(e.getCode()).isEqualTo("BASE_FARE_UNAVAILABLE"));
        verify(quoter, never()).quote(any(), any(), any(), any());
        assertThat(rows).isEmpty();
    }

    @Test
    void reviewWithoutPricingIsRefusedBeforeTheSupplier() {
        when(pricingService.requireInForce(T, PricingProduct.FLIGHT)).thenThrow(new PricingNotConfiguredException());

        assertThatThrownBy(() -> service.review(reviewRequest())).isInstanceOf(PricingNotConfiguredException.class);
        verify(provider, never()).review(any(FlightReviewRequest.class));
    }

    @Test
    void reviewWithADueReceivableOrBlockedBookingIsRefusedBeforeTheSupplier() {
        tenant.setBookingBlocked(true);
        assertThatThrownBy(() -> service.review(reviewRequest())).isInstanceOf(IllegalStateException.class);

        tenant.setBookingBlocked(false);
        when(walletService.hasDueReceivable(T)).thenReturn(true);
        assertThatThrownBy(() -> service.review(reviewRequest())).isInstanceOf(ReceivableDueException.class);

        verify(provider, never()).review(any(FlightReviewRequest.class));
    }

    @Test
    void confirmTwiceWithTheSameKeyBooksOnce() {
        String id = reviewed();

        PlatformBooking first = service.confirm(id, confirmRequest("key-00000001", "4500.00"));
        PlatformBooking second = service.confirm(id, confirmRequest("key-00000001", "4500.00"));

        assertThat(first.getStatus()).isEqualTo(PlatformBookingStatus.TICKETED);
        assertThat(second.getId()).isEqualTo(id);
        assertThat(second.getStatus()).isEqualTo(PlatformBookingStatus.TICKETED);
        verify(walletService, times(1)).hold(eq(T), any(), anyString(), anyString(), anyString());
        verify(provider, times(1)).book(any());
        verify(walletService, times(1)).debitHeld(anyString(), any(), anyString(), anyString(), anyString());
        assertThatThrownBy(() -> service.confirm(id, confirmRequest("key-00000002", "4500.00")))
                .isInstanceOf(IllegalStateException.class);
        verify(provider, times(1)).book(any());
    }

    @Test
    void confirmHoldsTheDisplayedFareAndBooksTheReviewedGross() {
        String id = reviewed();

        service.confirm(id, confirmRequest("key-00000001", "4500.00"));

        verify(walletService).hold(T, new BigDecimal("4500.00"), "pb-hold:" + id, REF, id);
        ArgumentCaptor<BookRequest> sent = ArgumentCaptor.forClass(BookRequest.class);
        verify(provider).book(sent.capture());
        assertThat(sent.getValue().reviewId()).isEqualTo("rv-1");
        assertThat(sent.getValue().totalFare()).isEqualByComparingTo("5000.00");
        assertThat(sent.getValue().passengers()).hasSize(3);
        assertThat(sent.getValue().passengers().get(0).passportNumber()).isEqualTo("N1234567");
        assertThat(sent.getValue().email()).isEqualTo("desk@agency.test");
        PlatformBooking b = rows.get(id);
        assertThat(b.getHoldEntryId()).isEqualTo("H1");
        assertThat(b.getIdempotencyKey()).isEqualTo("key-00000001");
        assertThat(b.getPaxEnc()).doesNotContain("Rahul");
        assertThat(paxCodec.decrypt(b.getPaxEnc(), Map.class).toString()).contains("Rahul");
    }

    @Test
    void eventsAreRecordedForEveryTransitionAndRedacted() {
        String id = reviewed();
        service.confirm(id, confirmRequest("key-00000001", "4500.00"));

        assertThat(savedEvents).extracting(PlatformBookingEvent::getType)
                .containsSubsequence("REVIEWED", "HELD", "BOOKING", "TICKETED");
        assertThat(savedEvents).allSatisfy(e -> assertThat(e.getPlatformBookingId()).isEqualTo(id));
        String all = savedEvents.stream().map(e -> String.valueOf(e.getPayload())).reduce("", String::concat);
        assertThat(all).doesNotContain("N1234567").doesNotContain("Rahul").doesNotContain("Sharma").doesNotContain("2019");
        PlatformBookingEvent booking = savedEvents.stream().filter(e -> e.getType().equals("BOOKING")).findFirst().orElseThrow();
        assertThat(booking.getPayload()).containsEntry("paxCount", 3);
        assertThat(booking.getPayload().get("types")).isEqualTo(List.of("ADULT", "ADULT", "CHILD"));
    }

    @Test
    void priceChangedAtReviewNeedsReconfirm() {
        String id = reviewed();

        assertThatThrownBy(() -> service.confirm(id, confirmRequest("key-00000001", "4400.00")))
                .isInstanceOfSatisfying(BookingConflictException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("PRICE_CHANGED");
                    assertThat((BigDecimal) e.getDetails().get("displayedFare")).isEqualByComparingTo("4500.00");
                });
        verify(walletService, never()).hold(any(), any(), any(), any(), any());
        verify(provider, never()).book(any());
        assertThat(rows.get(id).getStatus()).isEqualTo(PlatformBookingStatus.REVIEWING);
    }

    @Test
    void newPricingRowInForceNeedsReconfirm() {
        String id = reviewed();
        when(quoter.quote(eq(T), eq(PricingProduct.FLIGHT), any(), any())).thenReturn(quote("P2", "4400.00", "100.00", "0.00"));

        assertThatThrownBy(() -> service.confirm(id, confirmRequest("key-00000001", "4500.00")))
                .isInstanceOfSatisfying(BookingConflictException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("PRICE_CHANGED");
                    assertThat((BigDecimal) e.getDetails().get("displayedFare")).isEqualByComparingTo("4400.00");
                });
        PlatformBooking b = rows.get(id);
        assertThat(b.getDisplayed()).isEqualByComparingTo("4400.00");
        assertThat(b.getPricingSnapshot()).containsEntry("pricingId", "P2");
        verify(walletService, never()).hold(any(), any(), any(), any(), any());

        service.confirm(id, confirmRequest("key-00000001", "4400.00"));
        verify(walletService).hold(T, new BigDecimal("4400.00"), "pb-hold:" + id, REF, id);
        verify(provider, times(1)).book(any());
    }

    @Test
    void expiredReviewIsRefused() {
        String id = reviewed();
        rows.get(id).setReviewExpiresAt(Instant.now().minusSeconds(1));

        assertThatThrownBy(() -> service.confirm(id, confirmRequest("key-00000001", "4500.00")))
                .isInstanceOfSatisfying(BookingConflictException.class, e -> assertThat(e.getCode()).isEqualTo("REVIEW_EXPIRED"));
        verify(walletService, never()).hold(any(), any(), any(), any(), any());
    }

    @Test
    void anExpiredHotelReviewSaysRateNotFare() {
        hotelSupplierQuotes("4500.00");
        String id = service.reviewHotel(hotelReviewRequest()).getBookingId();
        rows.get(id).setReviewExpiresAt(Instant.now().minusSeconds(1));

        assertThatThrownBy(() -> service.confirm(id, hotelConfirmRequest("key-00000001", "4500.00")))
                .isInstanceOfSatisfying(BookingConflictException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("REVIEW_EXPIRED");
                    assertThat(e.getMessage()).contains("rate").doesNotContain("fare");
                });
    }

    /** The poller sweeps an expired review to ABORTED; confirming it afterwards still says "review again". */
    @Test
    void confirmingAReviewTheSweepClosedIsReviewExpired() {
        String id = reviewed();
        rows.get(id).setReviewExpiresAt(Instant.now().minusSeconds(1));
        service.sweepAbort(id, PlatformBookingStatus.REVIEWING, "Review expired");

        assertThatThrownBy(() -> service.confirm(id, confirmRequest("key-00000001", "4500.00")))
                .isInstanceOfSatisfying(BookingConflictException.class, e -> assertThat(e.getCode()).isEqualTo("REVIEW_EXPIRED"));
        verify(walletService, never()).hold(any(), any(), any(), any(), any());
    }

    @Test
    void shortWalletIs409AndNoSupplierCall() {
        String id = reviewed();
        when(walletService.hold(eq(T), any(), anyString(), anyString(), anyString()))
                .thenThrow(new InsufficientBalanceException(new BigDecimal("600.00")));

        assertThatThrownBy(() -> service.confirm(id, confirmRequest("key-00000001", "4500.00")))
                .isInstanceOf(InsufficientBalanceException.class);
        verify(provider, never()).book(any());
        assertThat(rows.get(id).getStatus()).isEqualTo(PlatformBookingStatus.REVIEWING);
    }

    @Test
    void uatBookingOnANonTestWalletIsRefused() {
        config.setProvider("tripjack");
        when(credentialRepository.findByProviderAndIsActiveTrue(SupplierProvider.TRIPJACK)).thenReturn(Optional.of(
                PlatformSupplierCredential.builder().id("CRED1").environment(SupplierEnvironment.UAT).build()));
        String id = reviewed();
        wallet.setIsTest(false);

        assertThatThrownBy(() -> service.confirm(id, confirmRequest("key-00000001", "4500.00")))
                .isInstanceOfSatisfying(BookingConflictException.class, e -> assertThat(e.getCode()).isEqualTo("TEST_WALLET_REQUIRED"));
        verify(walletService, never()).hold(any(), any(), any(), any(), any());
        verify(provider, never()).book(any());
    }

    @Test
    void mockBookingOnANonTestWalletIsRefused() {
        String id = reviewed();
        wallet.setIsTest(false);

        assertThatThrownBy(() -> service.confirm(id, confirmRequest("key-00000001", "4500.00")))
                .isInstanceOfSatisfying(BookingConflictException.class, e -> assertThat(e.getCode()).isEqualTo("TEST_WALLET_REQUIRED"));
        verify(provider, never()).book(any());
    }

    @Test
    void prodBookingOnATestWalletIsRefused() {
        config.setProvider("tripjack");
        when(settings.tripjackLiveReady()).thenReturn(true);
        when(credentialRepository.findByProviderAndIsActiveTrue(SupplierProvider.TRIPJACK)).thenReturn(Optional.of(
                PlatformSupplierCredential.builder().id("CRED1").environment(SupplierEnvironment.PROD).build()));
        String id = reviewed();

        assertThatThrownBy(() -> service.confirm(id, confirmRequest("key-00000001", "4500.00")))
                .isInstanceOfSatisfying(BookingConflictException.class, e -> assertThat(e.getCode()).isEqualTo("LIVE_WALLET_REQUIRED"));
        verify(walletService, never()).hold(any(), any(), any(), any(), any());
        verify(provider, never()).book(any());
    }

    @Test
    void ticketedDebitsAndCreditsCommission() {
        String id = reviewed();

        PlatformBooking b = service.confirm(id, confirmRequest("key-00000001", "4500.00"));

        verify(walletService).debitHeld("H1", new BigDecimal("4500.00"), "pb-debit:" + id, REF, id);
        verify(walletService).credit(eq(T), eq(WalletEntryType.COMMISSION_CREDIT), eq(new BigDecimal("100.00")),
                eq("pb-comm:" + id), eq(REF), eq(id), anyString());
        verify(walletService, never()).debitOrReceivable(any(), any(), any(), any(), any(), any(), any());
        assertThat(b.getStatus()).isEqualTo(PlatformBookingStatus.TICKETED);
        assertThat(b.getPnr()).isEqualTo("PNR123");
        assertThat(b.getTicketNumbers()).containsExactly("TKT-1", "TKT-2", "TKT-3");
        assertThat(b.getSupplierBookingId()).isEqualTo("SB-1");
        assertThat(b.getDebitEntryId()).isEqualTo("D1");
        assertThat(b.getTicketedAt()).isNotNull();
        verify(notificationService).bookingTicketed(b);
    }

    /** The mirror is written after the debit committed, and only marked PENDING when the booking names a CRM client. */
    @Test
    void ticketedWithAClientMarksTheMirrorPendingAndMirrorsAfterTheDebit() {
        when(mirrorService.hasLeadAgent(T, null, null)).thenReturn(true);
        FlightReviewRequest request = reviewRequest();
        request.setClientId("C1");
        String id = service.review(request).getBookingId();

        PlatformBooking b = service.confirm(id, confirmRequest("key-00000001", "4500.00"));

        assertThat(b.getMirrorStatus()).isEqualTo(com.voyra.crm.enums.MirrorStatus.PENDING);
        org.mockito.InOrder order = inOrder(walletService, mirrorService);
        order.verify(walletService).debitHeld(anyString(), any(), anyString(), anyString(), anyString());
        order.verify(mirrorService).sync(id);
    }

    /** A CRM-client booking needs an agent to own its mirror: refused before the supplier and before any hold. */
    @Test
    void aClientBookingWithNoIssuingAgentIsRefusedAtReviewAndConfirm() {
        FlightReviewRequest request = reviewRequest();
        request.setClientId("C1");

        assertThatThrownBy(() -> service.review(request))
                .isInstanceOf(com.voyra.crm.exception.BookingRequestException.class)
                .hasMessage("Choose the agent this booking is issued by")
                .extracting("code").isEqualTo("ISSUING_AGENT_REQUIRED");
        verify(provider, never()).review(any(FlightReviewRequest.class));

        // named at review, then dropped at confirm
        when(agentRepository.findByIdAndTenantId("A1", T)).thenReturn(Optional.of(Agent.builder().id("A1").build()));
        request.setIssuedByAgentId("A1");
        String id = service.review(request).getBookingId();
        assertThatThrownBy(() -> service.confirm(id, confirmRequest("key-00000001", "4500.00")))
                .isInstanceOf(com.voyra.crm.exception.BookingRequestException.class)
                .extracting("code").isEqualTo("ISSUING_AGENT_REQUIRED");
        verify(walletService, never()).hold(any(), any(), any(), any(), any());
        verify(provider, never()).book(any());
    }

    @Test
    void aStarterBookingWithNoClientHasNoMirror() {
        String id = reviewed();

        PlatformBooking b = service.confirm(id, confirmRequest("key-00000001", "4500.00"));

        assertThat(b.getMirrorStatus()).isEqualTo(com.voyra.crm.enums.MirrorStatus.NONE);
    }

    @Test
    void ticketedWithoutCommissionPostsOnlyTheDebit() {
        when(quoter.quote(eq(T), eq(PricingProduct.FLIGHT), any(), any())).thenReturn(quote("P1", "4500.00", "0.00", "0.00"));
        String id = reviewed();

        service.confirm(id, confirmRequest("key-00000001", "4500.00"));

        verify(walletService).debitHeld("H1", new BigDecimal("4500.00"), "pb-debit:" + id, REF, id);
        verify(walletService, never()).credit(any(), any(), any(), any(), any(), any(), any());
        verify(walletService, never()).debitOrReceivable(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void ticketedWithTdsPostsTdsEntry() {
        when(quoter.quote(eq(T), eq(PricingProduct.FLIGHT), any(), any())).thenReturn(quote("P1", "4500.00", "100.00", "5.00"));
        String id = reviewed();

        service.confirm(id, confirmRequest("key-00000001", "4500.00"));

        verify(walletService).credit(eq(T), eq(WalletEntryType.COMMISSION_CREDIT), eq(new BigDecimal("100.00")),
                eq("pb-comm:" + id), eq(REF), eq(id), anyString());
        verify(walletService).debitOrReceivable(eq(T), eq(WalletEntryType.TDS_DEDUCTED), eq(new BigDecimal("5.00")),
                eq("pb-tds:" + id), eq(REF), eq(id), eq(ReceivableReason.TDS_SHORTFALL));
    }

    @Test
    void negativeVoyraNetAlertsAndVarianceIsRecordedOnVoyraNet() {
        String id = reviewed();
        when(provider.book(any())).thenReturn(ticketed(new BigDecimal("5100.00")));

        PlatformBooking b = service.confirm(id, confirmRequest("key-00000001", "4500.00"));

        verify(walletService).debitHeld("H1", new BigDecimal("4500.00"), "pb-debit:" + id, REF, id);
        assertThat(b.getVoyraNet()).isEqualByComparingTo("-572.50");
        verify(notificationService, times(2)).platformAlert(anyString(), anyString());
        assertThat(savedEvents).extracting(PlatformBookingEvent::getType).contains("VARIANCE");
    }

    @Test
    void failedReleasesTheHold() {
        String id = reviewed();
        when(provider.book(any())).thenReturn(new BookResult("SB-1", SupplierBookingStatus.FAILED, null, List.of(), "Fare sold out", null));

        PlatformBooking b = service.confirm(id, confirmRequest("key-00000001", "4500.00"));

        verify(walletService).releaseHold("H1", "pb-release:" + id);
        verify(walletService, never()).debitHeld(any(), any(), any(), any(), any());
        assertThat(b.getStatus()).isEqualTo(PlatformBookingStatus.FAILED);
        assertThat(b.getSupplierMessage()).isEqualTo("Fare sold out");
        verify(notificationService).bookingFailed(b);
    }

    @Test
    void pendingStaysBooking() {
        String id = reviewed();
        when(provider.book(any())).thenReturn(new BookResult("SB-1", SupplierBookingStatus.PENDING, null, List.of(), null, null));

        PlatformBooking b = service.confirm(id, confirmRequest("key-00000001", "4500.00"));

        assertThat(b.getStatus()).isEqualTo(PlatformBookingStatus.BOOKING);
        assertThat(b.getSupplierBookingId()).isEqualTo("SB-1");
        verify(walletService, never()).releaseHold(any(), any());
        verify(walletService, never()).debitHeld(any(), any(), any(), any(), any());
    }

    @Test
    void timeoutLeavesBooking() {
        String id = reviewed();
        when(provider.book(any())).thenThrow(new SupplierTimeoutException("timed out", null));

        PlatformBooking b = service.confirm(id, confirmRequest("key-00000001", "4500.00"));

        assertThat(b.getStatus()).isEqualTo(PlatformBookingStatus.BOOKING);
        assertThat(rows.get(id).getHoldEntryId()).isEqualTo("H1");
        verify(walletService, never()).releaseHold(any(), any());
        verify(walletService, never()).debitHeld(any(), any(), any(), any(), any());
    }

    @Test
    void supplierErrorOnBookLeavesBooking() {
        String id = reviewed();
        when(provider.book(any())).thenThrow(new SupplierException("502 from supplier", null));

        PlatformBooking b = service.confirm(id, confirmRequest("key-00000001", "4500.00"));

        assertThat(b.getStatus()).isEqualTo(PlatformBookingStatus.BOOKING);
        verify(walletService, never()).releaseHold(any(), any());
    }

    @Test
    void ticketedTwiceDebitsOnce() {
        String id = reviewed();
        service.confirm(id, confirmRequest("key-00000001", "4500.00"));

        service.applySupplierResult(id, ticketed(null));

        verify(walletService, times(1)).debitHeld(anyString(), any(), anyString(), anyString(), anyString());
        verify(walletService, times(1)).credit(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void keyUsedOnAnotherBookingIsRefusedBeforeTheHold() {
        String id = reviewed();
        when(bookingRepository.findByIdempotencyKey("key-00000001")).thenReturn(Optional.of(PlatformBooking.builder().id("OTHER").build()));

        assertThatThrownBy(() -> service.confirm(id, confirmRequest("key-00000001", "4500.00"))).isInstanceOf(IllegalStateException.class);
        verify(walletService, never()).hold(any(), any(), any(), any(), any());
    }

    @Test
    void timeoutThenTicketedWithoutAChargedAmountKeepsVoyraNet() {
        String id = reviewed();
        when(provider.book(any())).thenThrow(new SupplierTimeoutException("timed out", null));
        service.confirm(id, confirmRequest("key-00000001", "4500.00"));

        // the poller asks by review id; the mock cannot read a fare from it
        PlatformBooking b = service.applySupplierResult(id, new BookResult("rv-1", SupplierBookingStatus.TICKETED, "PNR123",
                List.of("TKT-1"), "Ticketed", BigDecimal.ZERO));

        assertThat(b.getStatus()).isEqualTo(PlatformBookingStatus.TICKETED);
        assertThat(b.getVoyraNet()).isEqualByComparingTo("-472.50");
        assertThat(savedEvents).extracting(PlatformBookingEvent::getType).doesNotContain("VARIANCE");
        verify(notificationService, times(1)).platformAlert(anyString(), anyString());
    }

    @Test
    void sameKeyWithADifferentPayloadIs409() {
        String id = reviewed();
        service.confirm(id, confirmRequest("key-00000001", "4500.00"));
        PlatformBookingConfirmRequest otherPhone = confirmRequest("key-00000001", "4500.00");
        otherPhone.setContactPhone("9000000000");

        assertThatThrownBy(() -> service.confirm(id, otherPhone))
                .isInstanceOfSatisfying(BookingConflictException.class, e -> assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        assertThatThrownBy(() -> service.confirm(id, confirmRequest("key-00000001", "4400.00")))
                .isInstanceOfSatisfying(BookingConflictException.class, e -> assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        assertThat(rows.get(id).getConfirmPayloadHash()).hasSize(64);
        verify(provider, times(1)).book(any());
    }

    @Test
    void anEncryptionFailureHappensBeforeTheHold() {
        PlatformBookingService broken = new PlatformBookingService(bookingRepository, registry(), quoter, pricingService,
                walletService, walletRepository, tenantRepository, agentRepository, credentialRepository, notificationService,
                new PlatformBookingEventRecorder(eventRepository, paxCodec), new PaxCodec(objectMapper, "bad"), objectMapper,
                transactionManager, walletEntryRepository, platformAuditService, settings, mirrorService);
        String id = broken.review(reviewRequest()).getBookingId();

        assertThatThrownBy(() -> broken.confirm(id, confirmRequest("key-00000001", "4500.00"))).isInstanceOf(RuntimeException.class);
        verify(walletService, never()).hold(any(), any(), any(), any(), any());
    }

    @Test
    void theHoldCommitsBeforeTheSupplierIsCalled() {
        String id = reviewed();

        service.confirm(id, confirmRequest("key-00000001", "4500.00"));

        InOrder order = inOrder(walletService, transactionManager, provider);
        order.verify(walletService).hold(eq(T), any(), anyString(), anyString(), anyString());
        order.verify(transactionManager, calls(2)).commit(any()); // tx1 (hold) and tx2 (BOOKING)
        order.verify(provider).book(any());
    }

    @Test
    void confirmRefusesToRunInsideATransaction() {
        String id = reviewed();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> service.confirm(id, confirmRequest("key-00000001", "4500.00")))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verify(provider, never()).book(any());
    }

    @Test
    void passengerCountMustMatchTheReview() {
        String id = reviewed();
        PlatformBookingConfirmRequest req = confirmRequest("key-00000001", "4500.00");
        req.setPassengers(req.getPassengers().subList(0, 2));

        assertThatThrownBy(() -> service.confirm(id, req)).isInstanceOf(IllegalArgumentException.class);
        verify(walletService, never()).hold(any(), any(), any(), any(), any());
    }

    @Test
    void agentWithoutFlightServiceIs403() {
        String id = reviewed();
        rows.get(id).setBookedBy("AG1"); // their own booking: a colleague's would be 404 before the product check
        loginAs(UserType.AGENT, "AG1");
        when(agentRepository.findById("AG1")).thenReturn(Optional.of(
                Agent.builder().id("AG1").manageableServices(List.of(ServiceType.HOTEL)).build()));

        assertThatThrownBy(() -> service.review(reviewRequest())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.confirm(id, confirmRequest("key-00000001", "4500.00"))).isInstanceOf(AccessDeniedException.class);
        verify(walletService, never()).hold(any(), any(), any(), any(), any());

        loginAs(UserType.ACCOUNTANT, "ACC1");
        assertThatThrownBy(() -> service.review(reviewRequest())).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void agentWithFlightServiceBooksAsThemselves() {
        loginAs(UserType.AGENT, "AG1");
        when(agentRepository.findById("AG1")).thenReturn(Optional.of(
                Agent.builder().id("AG1").manageableServices(List.of(ServiceType.FLIGHT)).build()));
        String id = reviewed();

        PlatformBooking b = service.confirm(id, confirmRequest("key-00000001", "4500.00"));

        assertThat(b.getStatus()).isEqualTo(PlatformBookingStatus.TICKETED);
        assertThat(b.getIssuedByAgentId()).isEqualTo("AG1");
        assertThat(b.getBookedBy()).isEqualTo("AG1");
    }

    @Test
    void anotherAgencysBookingIsNotFound() {
        String id = reviewed();
        rows.get(id).setTenantId("T2");

        assertThatThrownBy(() -> service.confirm(id, confirmRequest("key-00000001", "4500.00")))
                .isInstanceOf(NotFoundException.class);
    }

    // ---- Task 5: admin resolve, late ticket, abort ----

    private String timedOut() {
        String id = reviewed();
        when(provider.book(any())).thenThrow(new SupplierTimeoutException("timed out", null));
        service.confirm(id, confirmRequest("key-00000001", "4500.00"));
        return id;
    }

    private static void loginAsPlatformAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("SA1", "root", UserType.SUPER_ADMIN, null), null));
    }

    @Test
    void adminResolveTicketedDebits() {
        String id = timedOut();
        rows.get(id).setStatus(PlatformBookingStatus.UNKNOWN);
        loginAsPlatformAdmin();

        PlatformBooking b = service.resolve(id, PlatformBookingStatus.TICKETED, "PNR9", List.of("TKT-9"), "Confirmed on Tripjack portal");

        assertThat(b.getStatus()).isEqualTo(PlatformBookingStatus.TICKETED);
        assertThat(b.getPnr()).isEqualTo("PNR9");
        assertThat(b.getTicketNumbers()).containsExactly("TKT-9");
        verify(walletService).debitHeld("H1", new BigDecimal("4500.00"), "pb-debit:" + id, REF, id);
        verify(walletService).credit(eq(T), eq(WalletEntryType.COMMISSION_CREDIT), any(), eq("pb-comm:" + id), eq(REF), eq(id), anyString());
        verify(walletService, never()).releaseHold(any(), any());
        PlatformBookingEvent resolved = savedEvents.stream().filter(e -> e.getType().equals("RESOLVED")).findFirst().orElseThrow();
        assertThat(String.valueOf(resolved.getPayload())).contains("SA1").contains("TICKETED");
        verify(platformAuditService).record(eq(PlatformAuditAction.BOOKING_RESOLVE), eq(REF), eq(id), any());
    }

    @Test
    void adminResolveFailedReleases() {
        String id = timedOut();
        loginAsPlatformAdmin();

        PlatformBooking b = service.resolve(id, PlatformBookingStatus.FAILED, null, null, "Tripjack has no such booking");

        assertThat(b.getStatus()).isEqualTo(PlatformBookingStatus.FAILED);
        assertThat(b.getResolvedFailedAt()).isNotNull();
        verify(walletService).releaseHold("H1", "pb-release:" + id);
        verify(walletService, never()).debitHeld(any(), any(), any(), any(), any());
        verify(platformAuditService).record(eq(PlatformAuditAction.BOOKING_RESOLVE), eq(REF), eq(id), any());
    }

    @Test
    void onlyAPlatformAdminCanResolve() {
        String id = timedOut();

        assertThatThrownBy(() -> service.resolve(id, PlatformBookingStatus.FAILED, null, null, "no"))
                .isInstanceOf(AccessDeniedException.class);
        verify(walletService, never()).releaseHold(any(), any());
    }

    @Test
    void resolveRefusesATerminalBookingAndATicketWithoutPnr() {
        String id = reviewed();
        service.confirm(id, confirmRequest("key-00000001", "4500.00")); // TICKETED
        loginAsPlatformAdmin();

        assertThatThrownBy(() -> service.resolve(id, PlatformBookingStatus.FAILED, null, null, "late"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.resolve(id, PlatformBookingStatus.TICKETED, " ", null, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(walletService, never()).releaseHold(any(), any());
    }

    @Test
    void lateTicketAfterResolveFailedDebitsViaReceivable() {
        String id = timedOut();
        loginAsPlatformAdmin();
        service.resolve(id, PlatformBookingStatus.FAILED, null, null, "Tripjack said no booking");

        // a poll that read TICKETED before the resolve committed applies it afterwards
        PlatformBooking b = service.applySupplierResult(id, ticketed(null));

        assertThat(b.getStatus()).isEqualTo(PlatformBookingStatus.TICKETED);
        assertThat(b.getDebitEntryId()).isEqualTo("D2");
        verify(walletService).debitOrReceivable(T, WalletEntryType.BOOKING_DEBIT, new BigDecimal("4500.00"), "pb-debit:" + id,
                REF, id, ReceivableReason.LATE_TICKET, "H1");
        verify(walletService, never()).debitHeld(any(), any(), any(), any(), any());
        verify(walletService).credit(eq(T), eq(WalletEntryType.COMMISSION_CREDIT), any(), eq("pb-comm:" + id), eq(REF), eq(id), anyString());
        verify(notificationService).platformAlert(contains("Late ticket"), anyString());
    }

    private void committedHold(String id) {
        walletEntries.put("pb-hold:" + id, WalletEntry.builder().id("H1").tenantId(T).type(WalletEntryType.HOLD)
                .idempotencyKey("pb-hold:" + id).build());
    }

    @Test
    void abortHeldReleasesTheHold() {
        String id = reviewed();
        committedHold(id);
        rows.get(id).setStatus(PlatformBookingStatus.HELD);
        rows.get(id).setHoldEntryId("H1");

        PlatformBooking b = service.abort(id);
        PlatformBooking again = service.abort(id);

        assertThat(b.getStatus()).isEqualTo(PlatformBookingStatus.ABORTED);
        assertThat(again.getStatus()).isEqualTo(PlatformBookingStatus.ABORTED);
        verify(walletService, times(1)).releaseHold("H1", "pb-release:" + id);
        assertThat(savedEvents).extracting(PlatformBookingEvent::getType).contains("ABORTED");
    }

    @Test
    void abortReviewingReleasesAnOrphanHold() {
        String id = reviewed();
        // tx1 rolled back after the REQUIRES_NEW hold committed: the row is REVIEWING with no holdEntryId
        committedHold(id);

        PlatformBooking b = service.abort(id);

        assertThat(b.getStatus()).isEqualTo(PlatformBookingStatus.ABORTED);
        verify(walletService).releaseHold("H1", "pb-release:" + id);
    }

    @Test
    void abortReviewingWithoutAHoldPostsNothing() {
        String id = reviewed();

        assertThat(service.abort(id).getStatus()).isEqualTo(PlatformBookingStatus.ABORTED);
        verify(walletService, never()).releaseHold(any(), any());
    }

    @Test
    void abortAfterTheBookWasSentIsRefused() {
        String id = timedOut();

        assertThatThrownBy(() -> service.abort(id)).isInstanceOf(IllegalStateException.class);
        verify(walletService, never()).releaseHold(any(), any());
    }

    @Test
    void anotherAgencyCannotAbort() {
        String id = reviewed();
        rows.get(id).setTenantId("T2");

        assertThatThrownBy(() -> service.abort(id)).isInstanceOf(NotFoundException.class);
    }

    // ---- an agent acts only on bookings they made; the owner on all of the agency's ----

    private String reviewedByAgent(String agentId) {
        loginAs(UserType.AGENT, agentId);
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(
                Agent.builder().id(agentId).manageableServices(List.of(ServiceType.FLIGHT)).build()));
        return reviewed();
    }

    @Test
    void anAgentCannotConfirmAbortQuoteOrCancelAColleaguesBooking() {
        String id = reviewedByAgent("AG1");
        rows.get(id).setStatus(PlatformBookingStatus.TICKETED);
        lenient().when(bookingRepository.findByIdAndTenantId(id, T)).thenAnswer(i -> Optional.of(rows.get(id)));
        loginAs(UserType.AGENT, "AG2");
        lenient().when(agentRepository.findById("AG2")).thenReturn(Optional.of(
                Agent.builder().id("AG2").manageableServices(List.of(ServiceType.FLIGHT)).build()));

        assertThatThrownBy(() -> service.confirm(id, confirmRequest("key-00000001", "4500.00"))).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.abort(id)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.cancelQuote(id)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.cancel(id, "cancel-key-1")).isInstanceOf(NotFoundException.class);
        verify(walletService, never()).hold(any(), any(), any(), any(), any());
        verify(provider, never()).cancel(any());
    }

    @Test
    void theOwnerCanActOnAnAgentsBooking() {
        String id = reviewedByAgent("AG1");
        lenient().when(bookingRepository.findByIdAndTenantId(id, T)).thenAnswer(i -> Optional.of(rows.get(id)));
        loginAs(UserType.AGENCY_OWNER, "OWNER1");

        assertThat(service.confirm(id, confirmRequest("key-00000001", "4500.00")).getStatus()).isEqualTo(PlatformBookingStatus.TICKETED);
        when(provider.cancelQuote(any())).thenReturn(new com.voyra.crm.models.booking.CancelQuote(new BigDecimal("4000.00"), null));
        when(settings.voyraCancelFee()).thenReturn(BigDecimal.ZERO);
        assertThatCode(() -> service.cancelQuote(id)).doesNotThrowAnyException();
        String other = reviewedByAgent("AG1");
        loginAs(UserType.AGENCY_OWNER, "OWNER1");
        assertThat(service.abort(other).getStatus()).isEqualTo(PlatformBookingStatus.ABORTED);
    }

    @Test
    void reviewKeepsAnArrayItineraryUnderTripInfos() {
        when(provider.review(any(FlightReviewRequest.class))).thenReturn(new FareReview("rv-2", new BigDecimal("5000.00"),
                new BigDecimal("4250.00"), "INR", "Refundable", Instant.now().plus(15, ChronoUnit.MINUTES),
                objectMapper.valueToTree(List.of(Map.of("sI", List.of(Map.of("id", "S1")))))));

        String id = reviewed();

        assertThat(rows.get(id).getItinerary()).containsOnlyKeys("tripInfos");
    }

    @Test
    void reviewKeepsAnObjectItineraryAsIs() {
        when(provider.review(any(FlightReviewRequest.class))).thenReturn(new FareReview("rv-3", new BigDecimal("5000.00"),
                new BigDecimal("4250.00"), "INR", "Refundable", Instant.now().plus(15, ChronoUnit.MINUTES),
                objectMapper.valueToTree(Map.of("tripInfos", List.of()))));

        assertThat(rows.get(reviewed()).getItinerary()).containsOnlyKeys("tripInfos");
    }

    // ---- hotels: the same state machine, routed to the hotel supplier ----

    private static final Instant FREE_UNTIL = Instant.parse("2026-11-29T00:00:00Z");

    private void hotelSupplierQuotes(String total) {
        lenient().when(pricingService.requireInForce(T, PricingProduct.HOTEL)).thenReturn(rules());
        lenient().when(hotelProvider.review(eq("O1"), eq("H1"), any(), any(), anyList()))
                .thenReturn(new FareReview("hrv-1", new BigDecimal(total), new BigDecimal("4250.00"), "INR", "Rate rules",
                        Instant.now().plus(15, ChronoUnit.MINUTES), null, "Free cancellation until 2026-11-29", FREE_UNTIL));
        lenient().when(quoter.quote(eq(T), eq(PricingProduct.HOTEL), any(), any())).thenReturn(quote("PH", "4500.00", "100.00", "0.00"));
        lenient().when(hotelProvider.book(any())).thenReturn(ticketed(null));
    }

    /** Two rooms: 2 adults + a child of 6, and 1 adult - four guests. */
    private static HotelReviewRequest hotelReviewRequest() {
        HotelReviewRequest r = new HotelReviewRequest();
        r.setHotelId("H1");
        r.setOptionId("O1");
        r.setCheckIn(LocalDate.now().plusDays(37));
        r.setCheckOut(LocalDate.now().plusDays(39));
        r.setRooms(List.of(new HotelRoomRequest(2, List.of(6)), new HotelRoomRequest(1, List.of())));
        r.setHotelName("Sea Breeze Resort");
        r.setCity("Goa");
        r.setRoomName("Deluxe Sea View");
        r.setMealBasis("Breakfast included");
        return r;
    }

    /** Lead guests of both rooms (room 0's with a PAN) and one more guest in room 0; the child's name is left out. */
    private static PlatformBookingConfirmRequest hotelConfirmRequest(String key, String accepted) {
        PlatformBookingConfirmRequest r = confirmRequest(key, accepted);
        r.setPassengers(List.of(
                PaxInput.builder().type("ADULT").title("Mr").firstName("Rahul").lastName("Sharma").roomIndex(0).pan("ABCDE1234F").build(),
                PaxInput.builder().type("ADULT").title("Mr").firstName("Vikram").lastName("Rao").roomIndex(1).build(),
                PaxInput.builder().type("ADULT").firstName("Priya").lastName("Sharma").roomIndex(0).build()));
        return r;
    }

    @Test
    void hotelReviewStampsAHotelRowWithTheStayAndPolicy() {
        hotelSupplierQuotes("5000.00");

        PlatformBookingReviewResponse res = service.reviewHotel(hotelReviewRequest());

        PlatformBooking b = rows.get(res.getBookingId());
        assertThat(b.getProduct()).isEqualTo(PricingProduct.HOTEL);
        assertThat(b.getSupplierReviewId()).isEqualTo("hrv-1");
        assertThat(b.getPaxCount()).isEqualTo(4);
        assertThat(b.getDisplayed()).isEqualByComparingTo("4500.00");
        assertThat(b.getPricingSnapshot()).containsEntry("pricingId", "PH");
        Map<String, Object> it = objectMapper.convertValue(b.getItinerary(), new com.fasterxml.jackson.core.type.TypeReference<>() { });
        assertThat(it).containsEntry("cancellationPolicy", "Free cancellation until 2026-11-29");
        assertThat(objectMapper.valueToTree(it).path("hotel").path("name").asText()).isEqualTo("Sea Breeze Resort");
        assertThat(objectMapper.valueToTree(it).path("stay").path("nights").asInt()).isEqualTo(2);
        assertThat(objectMapper.valueToTree(it).path("rooms").size()).isEqualTo(2);
        assertThat(res.getCancellationPolicy()).isEqualTo("Free cancellation until 2026-11-29");
        assertThat(res.getFreeCancellationUntil()).isEqualTo(FREE_UNTIL);
        verify(pricingService).requireInForce(T, PricingProduct.HOTEL);
        verify(provider, never()).review(any(FlightReviewRequest.class));
    }

    @Test
    void hotelReviewWithAMissingBaseRateIsAConflict() {
        hotelSupplierQuotes("5000.00");
        when(hotelProvider.review(eq("O1"), eq("H1"), any(), any(), anyList()))
                .thenReturn(new FareReview("hrv-1", new BigDecimal("5000.00"), null, "INR", null, null, null));

        assertThatThrownBy(() -> service.reviewHotel(hotelReviewRequest()))
                .isInstanceOfSatisfying(BookingConflictException.class, e -> assertThat(e.getCode()).isEqualTo("BASE_FARE_UNAVAILABLE"));
        assertThat(rows).isEmpty();
    }

    /** Review Focus 1: the room repriced between the hotel detail and review - the agency must accept the new price before any hold. */
    @Test
    void aRoomRepricedSinceTheDetailMustBeReconfirmed() {
        hotelSupplierQuotes("5000.00");
        HotelReviewRequest req = hotelReviewRequest();
        req.setSearchDisplayedFare(new BigDecimal("4300.00"));

        PlatformBookingReviewResponse res = service.reviewHotel(req);

        assertThat(res.isPriceChanged()).isTrue();
        assertThat(res.getPreviousDisplayedFare()).isEqualByComparingTo("4300.00");
        assertThatThrownBy(() -> service.confirm(res.getBookingId(), hotelConfirmRequest("key-00000001", "4300.00")))
                .isInstanceOfSatisfying(BookingConflictException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("PRICE_CHANGED");
                    assertThat(e.getMessage()).contains("rate").doesNotContain("fare");
                });
        verify(walletService, never()).hold(any(), any(), any(), any(), any());
        verify(hotelProvider, never()).book(any());

        PlatformBooking b = service.confirm(res.getBookingId(), hotelConfirmRequest("key-00000001", "4500.00"));
        assertThat(b.getStatus()).isEqualTo(PlatformBookingStatus.TICKETED);
    }

    /** Review Focus 5: an agent who handles flights only cannot book a hotel, nor confirm a hotel booking. */
    @Test
    void anAgentWithFlightOnlyCannotBookAHotel() {
        hotelSupplierQuotes("5000.00");
        String id = service.reviewHotel(hotelReviewRequest()).getBookingId();
        rows.get(id).setBookedBy("AG1");
        loginAs(UserType.AGENT, "AG1");
        when(agentRepository.findById("AG1")).thenReturn(Optional.of(
                Agent.builder().id("AG1").manageableServices(List.of(ServiceType.FLIGHT)).build()));

        assertThatThrownBy(() -> service.reviewHotel(hotelReviewRequest())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.confirm(id, hotelConfirmRequest("key-00000001", "4500.00"))).isInstanceOf(AccessDeniedException.class);
        verify(walletService, never()).hold(any(), any(), any(), any(), any());
        verify(hotelProvider, times(1)).review(any(), any(), any(), any(), anyList());
    }

    @Test
    void anAgentWhoHandlesHotelsBooksOne() {
        hotelSupplierQuotes("5000.00");
        loginAs(UserType.AGENT, "AG1");
        when(agentRepository.findById("AG1")).thenReturn(Optional.of(
                Agent.builder().id("AG1").manageableServices(List.of(ServiceType.HOTEL)).build()));

        String id = service.reviewHotel(hotelReviewRequest()).getBookingId();
        PlatformBooking b = service.confirm(id, hotelConfirmRequest("key-00000001", "4500.00"));

        assertThat(b.getStatus()).isEqualTo(PlatformBookingStatus.TICKETED);
        assertThat(b.getBookedBy()).isEqualTo("AG1");
    }

    /** confirm for a hotel reuses the flight path but books through the hotel supplier, one roomTravellerInfo group per room. */
    @Test
    void hotelConfirmBooksOnceThroughTheHotelSupplierWithGuestsPerRoom() {
        hotelSupplierQuotes("5000.00");
        String id = service.reviewHotel(hotelReviewRequest()).getBookingId();
        rows.get(id).setClientId("C1"); // a CRM client: the hotel gets its CRM mirror like a flight
        when(agentRepository.findByIdAndTenantId("AG1", T)).thenReturn(Optional.of(Agent.builder().id("AG1").build()));
        PlatformBookingConfirmRequest req = hotelConfirmRequest("key-00000001", "4500.00");
        req.setIssuedByAgentId("AG1");

        PlatformBooking b = service.confirm(id, req);

        ArgumentCaptor<BookRequest> sent = ArgumentCaptor.forClass(BookRequest.class);
        verify(hotelProvider, times(1)).book(sent.capture());
        verify(provider, never()).book(any());
        assertThat(sent.getValue().reviewId()).isEqualTo("hrv-1");
        assertThat(sent.getValue().rooms()).hasSize(2);
        assertThat(sent.getValue().rooms().get(0)).extracting(p -> p.firstName()).containsExactly("Rahul", "Priya");
        assertThat(sent.getValue().rooms().get(1)).extracting(p -> p.firstName()).containsExactly("Vikram");
        assertThat(sent.getValue().leadPan()).isEqualTo("ABCDE1234F");
        assertThat(b.getStatus()).isEqualTo(PlatformBookingStatus.TICKETED);
        assertThat(b.getMirrorStatus()).isEqualTo(com.voyra.crm.enums.MirrorStatus.PENDING);
        verify(mirrorService).sync(id);
        verify(walletService).debitHeld("H1", new BigDecimal("4500.00"), "pb-debit:" + id, REF, id);
        verify(walletService).credit(eq(T), eq(WalletEntryType.COMMISSION_CREDIT), any(), eq("pb-comm:" + id), eq(REF), eq(id),
                contains("hotel booking"));
    }

    @Test
    void hotelConfirmNeedsATitledLeadGuestInEveryRoom() {
        hotelSupplierQuotes("5000.00");
        String id = service.reviewHotel(hotelReviewRequest()).getBookingId();
        PlatformBookingConfirmRequest oneRoom = hotelConfirmRequest("key-00000001", "4500.00");
        oneRoom.setPassengers(oneRoom.getPassengers().subList(0, 1));
        PlatformBookingConfirmRequest untitled = hotelConfirmRequest("key-00000002", "4500.00");
        untitled.getPassengers().get(1).setTitle(null);

        assertThatThrownBy(() -> service.confirm(id, oneRoom)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.confirm(id, untitled)).isInstanceOf(IllegalArgumentException.class);
        verify(walletService, never()).hold(any(), any(), any(), any(), any());
    }

    /** Task 2 follow-up: the supplier prices the stay for each room's adults and child ages, not a bare room count. */
    @Test
    void hotelReviewSendsEachRoomsOccupancyToTheSupplier() {
        hotelSupplierQuotes("5000.00");

        service.reviewHotel(hotelReviewRequest());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<HotelRoomRequest>> rooms = ArgumentCaptor.forClass(List.class);
        verify(hotelProvider).review(eq("O1"), eq("H1"), any(), any(), rooms.capture());
        assertThat(rooms.getValue()).extracting(HotelRoomRequest::getAdults).containsExactly(2, 1);
        assertThat(rooms.getValue().get(0).getChildAges()).containsExactly(6);
    }

    @Test
    void aCheckInInThePastIsRefusedBeforeTheSupplier() {
        hotelSupplierQuotes("5000.00");
        HotelReviewRequest r = hotelReviewRequest();
        r.setCheckIn(LocalDate.now().minusDays(1));
        r.setCheckOut(LocalDate.now().plusDays(1));

        assertThatThrownBy(() -> service.reviewHotel(r)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("past");
        verify(hotelProvider, never()).review(any(), any(), any(), any(), anyList());
        assertThat(rows).isEmpty();
    }

    /** "Today" is India's (FinancialYear.today), not the server's: a check-in before the Indian today is refused. */
    @Test
    void thePastIsJudgedByTheIndianToday() {
        hotelSupplierQuotes("5000.00");
        HotelReviewRequest r = hotelReviewRequest(); // check-in today + 37 on the server clock
        try (org.mockito.MockedStatic<com.voyra.crm.util.FinancialYear> fy =
                     org.mockito.Mockito.mockStatic(com.voyra.crm.util.FinancialYear.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            fy.when(com.voyra.crm.util.FinancialYear::today).thenReturn(r.getCheckIn().plusDays(1));

            assertThatThrownBy(() -> service.reviewHotel(r)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("past");
        }
        verify(hotelProvider, never()).review(any(), any(), any(), any(), anyList());
    }

    /** Room 2 was reviewed for one adult: a second guest there is refused even though the booking total (4) is not exceeded. */
    @Test
    void moreGuestsInARoomThanItWasReviewedForAreRefused() {
        hotelSupplierQuotes("5000.00");
        String id = service.reviewHotel(hotelReviewRequest()).getBookingId();
        PlatformBookingConfirmRequest req = hotelConfirmRequest("key-00000001", "4500.00");
        req.setPassengers(List.of(
                PaxInput.builder().type("ADULT").title("Mr").firstName("Rahul").lastName("Sharma").roomIndex(0).build(),
                PaxInput.builder().type("ADULT").title("Mr").firstName("Vikram").lastName("Rao").roomIndex(1).build(),
                PaxInput.builder().type("ADULT").firstName("Asha").lastName("Rao").roomIndex(1).build()));

        assertThatThrownBy(() -> service.confirm(id, req)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("room 2");
        verify(walletService, never()).hold(any(), any(), any(), any(), any());
        verify(hotelProvider, never()).book(any());
    }

    /** Ownership first: an agent without hotels never learns that a colleague's hotel booking exists (404, not 403). */
    @Test
    void anAgentWithoutHotelsGetsNotFoundOnAColleaguesHotelBooking() {
        hotelSupplierQuotes("5000.00");
        String id = service.reviewHotel(hotelReviewRequest()).getBookingId();
        rows.get(id).setBookedBy("AG2");
        loginAs(UserType.AGENT, "AG1");
        lenient().when(agentRepository.findById("AG1")).thenReturn(Optional.of(
                Agent.builder().id("AG1").manageableServices(List.of(ServiceType.FLIGHT)).build()));

        assertThatThrownBy(() -> service.confirm(id, hotelConfirmRequest("key-00000001", "4500.00"))).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.abort(id)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.cancelQuote(id)).isInstanceOf(NotFoundException.class);
        verify(walletService, never()).hold(any(), any(), any(), any(), any());
    }

    /** A reviewed flight leaving on 1 Dec 2026, so each traveller's type can be worked out from their date of birth. */
    private String reviewedLeaving(String departure) {
        String id = reviewed();
        rows.get(id).setItinerary(Map.of("tripInfos", List.of(Map.of("sI", List.of(Map.of("dt", departure + "T06:00"))))));
        return id;
    }

    private static PlatformBookingConfirmRequest withPax(PaxInput... pax) {
        PlatformBookingConfirmRequest r = confirmRequest("key-00000001", "4500.00");
        r.setPassengers(List.of(pax));
        return r;
    }

    private static PaxInput pax(String type, LocalDate dob) {
        return PaxInput.builder().type(type).title("Mr").firstName("Rahul").lastName("Sharma").dob(dob).build();
    }

    /** Readiness: each traveller goes in the slot their date of birth gives on the travel date - else 400, before any hold. */
    @Test
    void aTravellerWhoseAgeDoesNotFitTheirSlotIsRefusedBeforeTheHold() {
        // turns 12 on 30 Nov 2026: an adult on the 1 Dec departure, though booked as a child
        String id = reviewedLeaving("2026-12-01");
        assertThatThrownBy(() -> service.confirm(id, withPax(pax("ADULT", null), pax("ADULT", LocalDate.of(1990, 1, 1)),
                pax("CHILD", LocalDate.of(2014, 11, 30)))))
                .isInstanceOf(com.voyra.crm.exception.BookingRequestException.class)
                .hasMessageContaining("Traveller 3").hasMessageContaining("adult")
                .extracting("code").isEqualTo("PAX_TYPE_MISMATCH");
        // an adult who is 11 on the travel date
        assertThatThrownBy(() -> service.confirm(id, withPax(pax("ADULT", LocalDate.of(2015, 1, 1)), pax("ADULT", null),
                pax("CHILD", LocalDate.of(2019, 5, 1)))))
                .extracting("code").isEqualTo("PAX_TYPE_MISMATCH");
        // a child who is still an infant on the travel date
        assertThatThrownBy(() -> service.confirm(id, withPax(pax("ADULT", null), pax("ADULT", null), pax("CHILD", LocalDate.of(2025, 1, 1)))))
                .extracting("code").isEqualTo("PAX_TYPE_MISMATCH");
        verify(walletService, never()).hold(any(), any(), any(), any(), any());
        verify(provider, never()).book(any());

        // the day before the 12th birthday is still a child
        assertThat(service.confirm(id, withPax(pax("ADULT", null), pax("ADULT", null), pax("CHILD", LocalDate.of(2014, 12, 2))))
                .getStatus()).isEqualTo(PlatformBookingStatus.TICKETED);
    }

    /**
     * R1 (regression LH7): a supplier answer without an itinerary (the mock with no route hints) still gets the age check,
     * from the travel date stamped at review out of the request's departure date.
     */
    @Test
    void theAgeCheckUsesTheTravelDateStampedAtReviewWhenTheRowHasNoItinerary() {
        FlightReviewRequest oneAdult = reviewRequest();
        oneAdult.setAdults(1);
        oneAdult.setChildren(0);
        oneAdult.setDepartDate("2026-12-01");
        String id = service.review(oneAdult).getBookingId();
        assertThat(rows.get(id).getItinerary()).isNull();
        assertThat(rows.get(id).getPricingSnapshot()).containsEntry("travelDate", "2026-12-01");

        // a five-year-old in the adult slot
        assertThatThrownBy(() -> service.confirm(id, withPax(pax("ADULT", LocalDate.of(2021, 6, 1)))))
                .isInstanceOf(com.voyra.crm.exception.BookingRequestException.class)
                .extracting("code").isEqualTo("PAX_TYPE_MISMATCH");

        FlightReviewRequest adultAndChild = reviewRequest();
        adultAndChild.setAdults(1);
        adultAndChild.setChildren(1);
        adultAndChild.setDepartDate("2026-12-01");
        String id2 = service.review(adultAndChild).getBookingId();
        // a thirty-year-old in the child slot
        assertThatThrownBy(() -> service.confirm(id2, withPax(pax("ADULT", null), pax("CHILD", LocalDate.of(1996, 1, 1)))))
                .isInstanceOf(com.voyra.crm.exception.BookingRequestException.class)
                .extracting("code").isEqualTo("PAX_TYPE_MISMATCH");

        verify(walletService, never()).hold(any(), any(), any(), any(), any());
        verify(provider, never()).book(any());
    }

    /** R1: the supplier's own itinerary date wins over the request hint; a re-price keeps the stamped date. */
    @Test
    void theStampedTravelDateComesFromTheReviewedItineraryFirstAndSurvivesARePrice() {
        com.fasterxml.jackson.databind.node.ObjectNode it = objectMapper.createObjectNode();
        it.putArray("sI").addObject().put("dt", "2026-11-20T06:00");
        when(provider.review(any(FlightReviewRequest.class))).thenReturn(new FareReview("rv-2", new BigDecimal("5000.00"),
                new BigDecimal("4250.00"), "INR", "Refundable", Instant.now().plus(15, ChronoUnit.MINUTES), it));
        FlightReviewRequest r = reviewRequest();
        r.setDepartDate("2026-12-01");
        String id = service.review(r).getBookingId();
        assertThat(rows.get(id).getPricingSnapshot()).containsEntry("travelDate", "2026-11-20");

        when(quoter.quote(eq(T), eq(PricingProduct.FLIGHT), any(), any())).thenReturn(quote("P2", "4600.00", "50.00", "0.00"));
        assertThatThrownBy(() -> service.confirm(id, confirmRequest("key-00000001", "4500.00")))
                .isInstanceOf(BookingConflictException.class)
                .satisfies(e -> assertThat(((BookingConflictException) e).getDetails()).containsEntry("agencyEarns", new BigDecimal("50.00")));
        assertThat(rows.get(id).getPricingSnapshot()).containsEntry("travelDate", "2026-11-20");
    }

    @Test
    void aChildOrInfantWithoutADateOfBirthIsRefused() {
        String id = reviewedLeaving("2026-12-01");
        assertThatThrownBy(() -> service.confirm(id, withPax(pax("ADULT", null), pax("ADULT", null), pax("CHILD", null))))
                .isInstanceOf(com.voyra.crm.exception.BookingRequestException.class)
                .hasMessageContaining("Traveller 3")
                .extracting("code").isEqualTo("PAX_DOB_REQUIRED");
        verify(walletService, never()).hold(any(), any(), any(), any(), any());
    }

    /** Slice 3 minor 5: the CRM client, lead and service are checked in the caller's tenant before the supplier is asked. */
    @Test
    void reviewChecksTheCrmLinksBeforeTheSupplier() {
        FlightReviewRequest request = reviewRequest();
        request.setClientId("C-OTHER");
        request.setLeadId("L1");
        request.setServiceId("S1");
        org.mockito.Mockito.doThrow(new IllegalArgumentException("Client not found: C-OTHER"))
                .when(mirrorService).requireLinks("C-OTHER", "L1", "S1");

        assertThatThrownBy(() -> service.review(request)).isInstanceOf(IllegalArgumentException.class).hasMessage("Client not found: C-OTHER");
        verify(provider, never()).review(any(FlightReviewRequest.class));
        assertThat(rows).isEmpty();
    }

    /** Fix round 1: the reviewed adult/child/infant mix is what the supplier priced - another mix of the same size is refused. */
    @Test
    void confirmingAnotherMixThanTheReviewedOneIsRefusedBeforeTheHold() {
        FlightReviewRequest request = reviewRequest();
        request.setAdults(1);
        request.setChildren(1);
        String id = service.review(request).getBookingId();
        assertThat(rows.get(id).getPricingSnapshot()).containsEntry("paxMix", Map.of("adults", 1, "children", 1, "infants", 0));

        assertThatThrownBy(() -> service.confirm(id, withPax(pax("ADULT", LocalDate.of(1990, 1, 1)), pax("ADULT", LocalDate.of(1985, 6, 1)))))
                .isInstanceOf(com.voyra.crm.exception.BookingRequestException.class)
                .extracting("code").isEqualTo("PAX_MIX_CHANGED");
        verify(walletService, never()).hold(any(), any(), any(), any(), any());
        verify(provider, never()).book(any());

        assertThat(service.confirm(id, withPax(pax("ADULT", null), pax("CHILD", LocalDate.of(2019, 5, 1)))).getStatus())
                .isEqualTo(PlatformBookingStatus.TICKETED);
    }

    /** Fix round 1: with a PROD credential active and tripjack_live_ready off, new bookings stop at review; nothing is asked. */
    @Test
    void aProdCredentialWithoutTheLiveSwitchRefusesNewBookings() {
        config.setProvider("tripjack");
        when(credentialRepository.findByProviderAndIsActiveTrue(SupplierProvider.TRIPJACK)).thenReturn(Optional.of(
                PlatformSupplierCredential.builder().id("CRED1").environment(SupplierEnvironment.PROD).build()));
        when(settings.tripjackLiveReady()).thenReturn(false);

        assertThatThrownBy(() -> service.review(reviewRequest()))
                .isInstanceOfSatisfying(BookingConflictException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("LIVE_BOOKINGS_PAUSED");
                    assertThat(e.getMessage()).doesNotContain("tripjack_live_ready").doesNotContain("PROD");
                });
        verify(provider, never()).review(any(FlightReviewRequest.class));
        assertThat(rows).isEmpty();
    }

    /** Fix round 2: a live row reviewed while the switch was on, confirmed after it went off, is refused before any hold or book. */
    @Test
    void aLiveReviewConfirmedAfterTheSwitchWentOffIsRefused() {
        config.setProvider("tripjack");
        when(credentialRepository.findByProviderAndIsActiveTrue(SupplierProvider.TRIPJACK)).thenReturn(Optional.of(
                PlatformSupplierCredential.builder().id("CRED1").environment(SupplierEnvironment.PROD).build()));
        when(settings.tripjackLiveReady()).thenReturn(true, false);
        wallet.setIsTest(false);
        String id = reviewed();

        assertThatThrownBy(() -> service.confirm(id, confirmRequest("key-00000001", "4500.00")))
                .isInstanceOfSatisfying(BookingConflictException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("LIVE_BOOKINGS_PAUSED");
                    assertThat(e.getMessage()).doesNotContain("tripjack_live_ready").doesNotContain("PROD");
                });
        verify(walletService, never()).hold(any(), any(), any(), any(), any());
        verify(provider, never()).book(any());
        assertThat(rows.get(id).getStatus()).isEqualTo(PlatformBookingStatus.REVIEWING);
    }

    /** Final review minor 3: replaying the same confirm key on a live row after the switch went off returns it, held or ticketed. */
    @Test
    void aSameKeyReplayOfAHeldOrTicketedLiveRowStillAnswersWithTheSwitchOff() {
        config.setProvider("tripjack");
        when(credentialRepository.findByProviderAndIsActiveTrue(SupplierProvider.TRIPJACK)).thenReturn(Optional.of(
                PlatformSupplierCredential.builder().id("CRED1").environment(SupplierEnvironment.PROD).build()));
        when(settings.tripjackLiveReady()).thenReturn(true, true, false); // on for review and confirm, then off
        wallet.setIsTest(false);
        String id = reviewed();
        PlatformBookingConfirmRequest request = confirmRequest("key-00000001", "4500.00");
        assertThat(service.confirm(id, request).getStatus()).isEqualTo(PlatformBookingStatus.TICKETED);

        assertThat(service.confirm(id, request).getStatus()).isEqualTo(PlatformBookingStatus.TICKETED);
        rows.get(id).setStatus(PlatformBookingStatus.HELD);
        assertThat(service.confirm(id, request).getStatus()).isEqualTo(PlatformBookingStatus.HELD);

        verify(provider, times(1)).book(any());
        verify(walletService, times(1)).hold(any(), any(), any(), any(), any());
    }

    /** Final review Important 1: a Tripjack review confirmed after the provider went back to the mock is refused; the mock never books it. */
    @Test
    void aTripjackReviewIsNotBookedThroughTheMock() {
        config.setProvider("tripjack");
        when(credentialRepository.findByProviderAndIsActiveTrue(SupplierProvider.TRIPJACK)).thenReturn(Optional.of(
                PlatformSupplierCredential.builder().id("CRED1").environment(SupplierEnvironment.UAT).build()));
        String id = reviewed();
        config.setProvider("mock"); // restarted on the mock

        assertThatThrownBy(() -> service.confirm(id, confirmRequest("key-00000001", "4500.00")))
                .isInstanceOfSatisfying(BookingConflictException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("BOOKING_UNAVAILABLE");
                    assertThat(e.getMessage()).isEqualTo("This booking can't be changed right now. Voyra has been notified.");
                });
        verify(walletService, never()).hold(any(), any(), any(), any(), any());
        verify(provider, never()).book(any());
        assertThat(rows.get(id).getStatus()).isEqualTo(PlatformBookingStatus.REVIEWING);
        verify(notificationService).platformAlert(org.mockito.ArgumentMatchers.contains(id), anyString());
        assertThat(savedEvents).filteredOn(e -> e.getType().equals("SUPPLIER_MISMATCH")).hasSize(1);
    }

    private static AgencyPricingService.EffectivePricing rules() {
        return new AgencyPricingService.EffectivePricing(PricingRule.builder().id("D1").version(1).build(),
                PricingRule.builder().id("C1").version(1).build());
    }
}
