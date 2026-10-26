package com.voyra.crm.service;

import com.voyra.crm.entity.PlatformReceivable;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.entity.Wallet;
import com.voyra.crm.entity.WalletEntry;
import com.voyra.crm.enums.ReceivableReason;
import com.voyra.crm.enums.ReceivableStatus;
import com.voyra.crm.enums.TenantStatus;
import com.voyra.crm.enums.TopupStatus;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.enums.WalletEntryType;
import com.voyra.crm.enums.WalletStatus;
import com.voyra.crm.exception.InsufficientBalanceException;
import com.voyra.crm.exception.ReceivableDueException;
import com.voyra.crm.repository.PlatformReceivableRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.repository.TopupRequestRepository;
import com.voyra.crm.repository.WalletEntryRepository;
import com.voyra.crm.repository.WalletRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    private static final String T = "T1";

    @Mock
    private WalletRepository walletRepository;
    @Mock
    private WalletEntryRepository entryRepository;
    @Mock
    private PlatformReceivableRepository receivableRepository;
    @Mock
    private TopupRequestRepository topupRequestRepository;
    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private NotificationService notificationService;
    @InjectMocks
    private WalletService service;

    private Wallet wallet;
    private Tenant tenant;
    private final List<WalletEntry> entries = new ArrayList<>();
    private final List<PlatformReceivable> receivables = new ArrayList<>();

    @BeforeEach
    void setUp() {
        wallet = Wallet.builder().id("W1").tenantId(T).balance(BigDecimal.ZERO).held(BigDecimal.ZERO)
                .status(WalletStatus.OPEN).createdAt(Instant.now()).build();
        tenant = Tenant.builder().id(T).status(TenantStatus.ACTIVE).bookingBlocked(false).build();

        lenient().when(walletRepository.findByTenantIdForUpdate(T)).thenAnswer(i -> Optional.of(wallet));
        lenient().when(walletRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(tenantRepository.findById(T)).thenAnswer(i -> Optional.of(tenant));

        lenient().when(entryRepository.existsById(anyString())).thenReturn(false);
        lenient().when(entryRepository.save(any())).thenAnswer(i -> {
            entries.add(i.getArgument(0));
            return i.getArgument(0);
        });
        lenient().when(entryRepository.findByIdempotencyKey(anyString())).thenAnswer(i -> entries.stream()
                .filter(e -> e.getIdempotencyKey().equals(i.getArgument(0))).findFirst());
        lenient().when(entryRepository.findById(anyString())).thenAnswer(i -> entries.stream()
                .filter(e -> e.getId().equals(i.getArgument(0))).findFirst());
        lenient().when(entryRepository.existsByRelatedEntryIdAndType(anyString(), any())).thenAnswer(i -> entries.stream()
                .anyMatch(e -> Objects.equals(e.getRelatedEntryId(), i.getArgument(0)) && e.getType() == i.getArgument(1)));
        lenient().when(entryRepository.findFirstByRelatedEntryIdAndType(anyString(), any())).thenAnswer(i -> entries.stream()
                .filter(e -> Objects.equals(e.getRelatedEntryId(), i.getArgument(0)) && e.getType() == i.getArgument(1))
                .findFirst());
        lenient().when(entryRepository.findOpenHolds("W1")).thenAnswer(i -> entries.stream()
                .filter(h -> h.getType() == WalletEntryType.HOLD)
                .filter(h -> entries.stream().noneMatch(r -> r.getType() == WalletEntryType.HOLD_RELEASE
                        && h.getId().equals(r.getRelatedEntryId())))
                .toList());

        lenient().when(receivableRepository.existsById(anyString())).thenReturn(false);
        lenient().when(receivableRepository.save(any())).thenAnswer(i -> {
            PlatformReceivable r = i.getArgument(0);
            if (!receivables.contains(r)) {
                receivables.add(r);
            }
            return r;
        });
        lenient().when(receivableRepository.findByTenantIdAndStatusOrderByCreatedAt(T, ReceivableStatus.DUE))
                .thenAnswer(i -> receivables.stream().filter(r -> r.getStatus() == ReceivableStatus.DUE).toList());
        lenient().when(receivableRepository.existsByTenantIdAndStatus(T, ReceivableStatus.DUE))
                .thenAnswer(i -> receivables.stream().anyMatch(r -> r.getStatus() == ReceivableStatus.DUE));

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("U1", "owner@agency.test", UserType.AGENCY_OWNER, T), null));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private void fund(String balance) {
        wallet.setBalance(d(balance));
    }

    private PlatformReceivable due(String id, String amount, Instant createdAt) {
        PlatformReceivable r = PlatformReceivable.builder().id(id).tenantId(T).amount(d(amount))
                .originalAmount(d(amount)).reason(ReceivableReason.CHARGEBACK).status(ReceivableStatus.DUE)
                .createdAt(createdAt).build();
        receivables.add(r);
        return r;
    }

    private static BigDecimal eqAmount(String v) {
        return argThat(a -> a != null && a.compareTo(new BigDecimal(v)) == 0);
    }

    @Test
    void sameIdempotencyKeyPostsOnce() {
        WalletEntry first = service.credit(T, WalletEntryType.TOPUP, d("500"), "k1", "TOPUP_REQUEST", "R1", "Top-up");
        WalletEntry again = service.credit(T, WalletEntryType.TOPUP, d("500"), "k1", "TOPUP_REQUEST", "R1", "Top-up");

        assertThat(again).isSameAs(first);
        assertThat(entries).hasSize(1);
        assertThat(wallet.getBalance()).isEqualByComparingTo("500");
        assertThat(first.getBalanceAfter()).isEqualByComparingTo("500");
        assertThat(first.getHeldAfter()).isEqualByComparingTo("0");
        assertThat(first.getCreatedBy()).isEqualTo("U1");
        assertThat(first.getCreatedByName()).isEqualTo("owner@agency.test");
    }

    @Test
    void aSystemPostingIsAttributedToVoyra() {
        SecurityContextHolder.clearContext();
        WalletEntry entry = service.credit(T, WalletEntryType.TOPUP, d("10"), "k1", null, null, null);
        assertThat(entry.getCreatedBy()).isEqualTo("system");
        assertThat(entry.getCreatedByName()).isEqualTo("Voyra");
    }

    @Test
    void holdRefusedWhenAvailableIsShort() {
        fund("1000");
        service.hold(T, d("400"), "h1", "PLATFORM_BOOKING", "B1");

        assertThatThrownBy(() -> service.hold(T, d("700"), "h2", "PLATFORM_BOOKING", "B2"))
                .isInstanceOfSatisfying(InsufficientBalanceException.class,
                        e -> assertThat(e.getAvailable()).isEqualByComparingTo("600"));
        assertThat(entries).hasSize(1);
        assertThat(wallet.getHeld()).isEqualByComparingTo("400");
    }

    @Test
    void holdRefusedWhileAReceivableIsDue() {
        fund("1000");
        due("R1", "50", Instant.now());

        assertThatThrownBy(() -> service.hold(T, d("100"), "h1", "PLATFORM_BOOKING", "B1"))
                .isInstanceOf(ReceivableDueException.class);
        assertThat(entries).isEmpty();
    }

    @Test
    void holdRefusedOnAClosedWallet() {
        fund("1000");
        wallet.setStatus(WalletStatus.CLOSED);

        assertThatThrownBy(() -> service.hold(T, d("100"), "h1", "PLATFORM_BOOKING", "B1"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
        assertThat(entries).isEmpty();
    }

    @Test
    void holdRefusedForASuspendedAgency() {
        fund("1000");
        tenant.setStatus(TenantStatus.SUSPENDED);

        assertThatThrownBy(() -> service.hold(T, d("100"), "h1", "PLATFORM_BOOKING", "B1"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("suspended");
        assertThat(entries).isEmpty();
    }

    @Test
    void holdRefusedWhenBookingIsBlocked() {
        fund("1000");
        tenant.setBookingBlocked(true);

        assertThatThrownBy(() -> service.hold(T, d("100"), "h1", "PLATFORM_BOOKING", "B1"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("blocked");
        assertThat(entries).isEmpty();
    }

    @Test
    void creditSettlesDueReceivablesFirst() {
        PlatformReceivable older = due("R1", "300", Instant.parse("2026-10-01T00:00:00Z"));
        PlatformReceivable newer = due("R2", "500", Instant.parse("2026-10-02T00:00:00Z"));

        WalletEntry topup = service.credit(T, WalletEntryType.TOPUP, d("600"), "gw:pay_1", "TOPUP_REQUEST", "TR1", "Top-up");

        assertThat(topup.getType()).isEqualTo(WalletEntryType.TOPUP);
        assertThat(topup.getAmount()).isEqualByComparingTo("600");
        assertThat(entries).extracting(WalletEntry::getType).containsExactly(WalletEntryType.TOPUP,
                WalletEntryType.RECEIVABLE_SETTLED, WalletEntryType.RECEIVABLE_SETTLED);
        assertThat(entries.get(1).getAmount()).isEqualByComparingTo("-300");
        assertThat(entries.get(1).getRefId()).isEqualTo("R1");
        // R16: the statement says why in words, never the reason code
        assertThat(entries.get(1).getNarration()).isEqualTo("Amount due to Voyra settled: a payment reversed by the bank (chargeback)");
        assertThat(entries.get(2).getAmount()).isEqualByComparingTo("-300");
        assertThat(entries.get(2).getRefId()).isEqualTo("R2");

        assertThat(older.getStatus()).isEqualTo(ReceivableStatus.SETTLED);
        assertThat(older.getAmount()).isEqualByComparingTo("0");
        assertThat(older.getSettledAt()).isNotNull();
        assertThat(newer.getStatus()).isEqualTo(ReceivableStatus.DUE);
        assertThat(newer.getAmount()).isEqualByComparingTo("200");
        assertThat(newer.getOriginalAmount()).isEqualByComparingTo("500");
        assertThat(wallet.getBalance()).isEqualByComparingTo("0");
    }

    @Test
    void aCreditSmallerThanTheReceivableSettlesItPartially() {
        PlatformReceivable r = due("R1", "300", Instant.now());

        service.credit(T, WalletEntryType.TOPUP, d("200"), "k1", null, null, null);

        assertThat(r.getStatus()).isEqualTo(ReceivableStatus.DUE);
        assertThat(r.getAmount()).isEqualByComparingTo("100");
        assertThat(wallet.getBalance()).isEqualByComparingTo("0");
        assertThat(service.hasDueReceivable(T)).isTrue();
    }

    @Test
    void debitOrReceivablePostsWhatIsAvailableAndOpensTheRest() {
        fund("100");

        WalletEntry entry = service.debitOrReceivable(T, WalletEntryType.COMMISSION_RECALL, d("250"), "rc1",
                "PLATFORM_BOOKING", "B1", ReceivableReason.CANCEL_NET_NEGATIVE);

        assertThat(entry.getType()).isEqualTo(WalletEntryType.COMMISSION_RECALL);
        assertThat(entry.getAmount()).isEqualByComparingTo("-100");
        assertThat(wallet.getBalance()).isEqualByComparingTo("0");
        assertThat(receivables).singleElement().satisfies(r -> {
            assertThat(r.getAmount()).isEqualByComparingTo("150");
            assertThat(r.getOriginalAmount()).isEqualByComparingTo("150");
            assertThat(r.getStatus()).isEqualTo(ReceivableStatus.DUE);
            assertThat(r.getReason()).isEqualTo(ReceivableReason.CANCEL_NET_NEGATIVE);
            assertThat(r.getRefId()).isEqualTo("B1");
        });
    }

    @Test
    void debitHeldReleasesTheHoldAndDebitsOnce() {
        fund("1000");
        WalletEntry hold = service.hold(T, d("400"), "h1", "PLATFORM_BOOKING", "B1");

        WalletEntry debit = service.debitHeld(hold.getId(), d("400"), "d1", "PLATFORM_BOOKING", "B1");
        WalletEntry again = service.debitHeld(hold.getId(), d("400"), "d1", "PLATFORM_BOOKING", "B1");

        assertThat(again).isSameAs(debit);
        assertThat(entries).extracting(WalletEntry::getType).containsExactly(WalletEntryType.HOLD,
                WalletEntryType.HOLD_RELEASE, WalletEntryType.BOOKING_DEBIT);
        assertThat(entries.get(1).getAmount()).isEqualByComparingTo("-400");
        assertThat(entries.get(1).getRelatedEntryId()).isEqualTo(hold.getId());
        assertThat(debit.getAmount()).isEqualByComparingTo("-400");
        assertThat(wallet.getBalance()).isEqualByComparingTo("600");
        assertThat(wallet.getHeld()).isEqualByComparingTo("0");
    }

    @Test
    void debitHeldAboveTheHoldOpensALateTicketReceivableForTheShortfall() {
        fund("1000");
        WalletEntry hold = service.hold(T, d("400"), "h1", "PLATFORM_BOOKING", "B1");

        WalletEntry debit = service.debitHeld(hold.getId(), d("1200"), "d1", "PLATFORM_BOOKING", "B1");

        assertThat(debit.getAmount()).isEqualByComparingTo("-1000");
        assertThat(wallet.getBalance()).isEqualByComparingTo("0");
        assertThat(wallet.getHeld()).isEqualByComparingTo("0");
        assertThat(receivables).singleElement().satisfies(r -> {
            assertThat(r.getAmount()).isEqualByComparingTo("200");
            assertThat(r.getReason()).isEqualTo(ReceivableReason.LATE_TICKET);
        });
        verify(notificationService).platformAlert(anyString(), anyString());
    }

    @Test
    void lateBookingDebitAfterTheHoldWasReleasedOpensALateTicketReceivable() {
        fund("400");
        WalletEntry hold = service.hold(T, d("400"), "h1", "PLATFORM_BOOKING", "B1");
        service.releaseHold(hold.getId(), "pb-release:B1");
        fund("300"); // the released 400 was partly spent before the late ticket arrived

        WalletEntry debit = service.debitOrReceivable(T, WalletEntryType.BOOKING_DEBIT, d("400"), "pb-debit:B1",
                "PLATFORM_BOOKING", "B1", ReceivableReason.LATE_TICKET, hold.getId());
        WalletEntry again = service.debitOrReceivable(T, WalletEntryType.BOOKING_DEBIT, d("400"), "pb-debit:B1",
                "PLATFORM_BOOKING", "B1", ReceivableReason.LATE_TICKET, hold.getId());

        assertThat(again).isSameAs(debit);
        assertThat(debit.getType()).isEqualTo(WalletEntryType.BOOKING_DEBIT);
        assertThat(debit.getAmount()).isEqualByComparingTo("-300");
        assertThat(debit.getRelatedEntryId()).isEqualTo(hold.getId());
        assertThat(wallet.getBalance()).isEqualByComparingTo("0");
        assertThat(receivables).singleElement().satisfies(r -> {
            assertThat(r.getAmount()).isEqualByComparingTo("100");
            assertThat(r.getReason()).isEqualTo(ReceivableReason.LATE_TICKET);
        });
    }

    @Test
    void releasingAClosedHoldIsRefused() {
        fund("1000");
        WalletEntry hold = service.hold(T, d("400"), "h1", "PLATFORM_BOOKING", "B1");
        service.releaseHold(hold.getId(), "r1");

        assertThatThrownBy(() -> service.releaseHold(hold.getId(), "r2"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("released");
        assertThat(wallet.getHeld()).isEqualByComparingTo("0");
        assertThat(wallet.getBalance()).isEqualByComparingTo("1000");
    }

    @Test
    void adjustmentBelowZeroIsRefused() {
        fund("100");

        assertThatThrownBy(() -> service.adjust(T, d("-150"), "Bank charge", "adj:c1"))
                .isInstanceOfSatisfying(InsufficientBalanceException.class,
                        e -> assertThat(e.getAvailable()).isEqualByComparingTo("100"));
        assertThat(entries).isEmpty();

        WalletEntry ok = service.adjust(T, d("-40"), "Bank charge", "adj:c2");
        assertThat(ok.getType()).isEqualTo(WalletEntryType.ADJUSTMENT);
        assertThat(ok.getNarration()).isEqualTo("Bank charge");
        assertThat(wallet.getBalance()).isEqualByComparingTo("60");
    }

    @Test
    void sameAdjustmentKeyPostsOnce() {
        fund("100");

        WalletEntry first = service.adjust(T, d("-40"), "Bank charge", "adj:req-1");
        WalletEntry again = service.adjust(T, d("-40"), "Bank charge", "adj:req-1");

        assertThat(again).isSameAs(first);
        assertThat(first.getIdempotencyKey()).isEqualTo("adj:req-1");
        assertThat(entries).hasSize(1);
        assertThat(wallet.getBalance()).isEqualByComparingTo("60");
    }

    @Test
    void aKeyAlreadyUsedOnAnotherWalletIsRefusedNotReplayed() {
        fund("100");
        entries.add(WalletEntry.builder().id("E-OTHER").walletId("W2").tenantId("T2")
                .type(WalletEntryType.ADJUSTMENT).amount(d("50")).idempotencyKey("shared").build());

        assertThatThrownBy(() -> service.adjust(T, d("50"), "Goodwill", "shared"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("This request id was already used on another wallet");
        assertThatThrownBy(() -> service.credit(T, WalletEntryType.TOPUP, d("50"), "shared", null, null, null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.hold(T, d("50"), "shared", null, null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.debitOrReceivable(T, WalletEntryType.ADJUSTMENT, d("50"), "shared", null, null,
                ReceivableReason.CHARGEBACK)).isInstanceOf(IllegalStateException.class);
        assertThat(entries).hasSize(1);
        assertThat(wallet.getBalance()).isEqualByComparingTo("100");
    }

    @Test
    void debitHeldTwiceWithDifferentKeysDebitsOnce() {
        fund("1000");
        WalletEntry hold = service.hold(T, d("400"), "h1", "PLATFORM_BOOKING", "B1");

        WalletEntry debit = service.debitHeld(hold.getId(), d("400"), "d1", "PLATFORM_BOOKING", "B1");
        // the hold is now released by the first debitHeld; a retry under a fresh key must still find that debit
        WalletEntry again = service.debitHeld(hold.getId(), d("400"), "d2", "PLATFORM_BOOKING", "B1");

        assertThat(again).isSameAs(debit);
        assertThat(debit.getRelatedEntryId()).isEqualTo(hold.getId());
        assertThat(entries).extracting(WalletEntry::getType).containsExactly(WalletEntryType.HOLD,
                WalletEntryType.HOLD_RELEASE, WalletEntryType.BOOKING_DEBIT);
        assertThat(wallet.getBalance()).isEqualByComparingTo("600");
    }

    @Test
    void closeRefusedWithAnOpenHold() {
        fund("1000");
        service.hold(T, d("400"), "h1", "PLATFORM_BOOKING", "B1");

        assertThatThrownBy(() -> service.close(T, "UTR1"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("hold");
        assertThat(wallet.getStatus()).isEqualTo(WalletStatus.OPEN);
    }

    @Test
    void closeRefusedWithADueReceivable() {
        due("R1", "50", Instant.now());

        assertThatThrownBy(() -> service.close(T, "UTR1"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("receivable");
        assertThat(wallet.getStatus()).isEqualTo(WalletStatus.OPEN);
    }

    @Test
    void closeRefusedWithAPendingTopup() {
        fund("100");
        when(topupRequestRepository.existsByWalletIdAndStatus("W1", TopupStatus.PENDING)).thenReturn(true);

        assertThatThrownBy(() -> service.close(T, "UTR1"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("top-up");
        assertThat(wallet.getStatus()).isEqualTo(WalletStatus.OPEN);
        assertThat(entries).isEmpty();
    }

    @Test
    void closePostsClosureRefundOfTheBalance() {
        fund("750");

        WalletEntry refund = service.close(T, "UTR123");

        assertThat(refund.getType()).isEqualTo(WalletEntryType.CLOSURE_REFUND);
        assertThat(refund.getAmount()).isEqualByComparingTo("-750");
        assertThat(wallet.getBalance()).isEqualByComparingTo("0");
        assertThat(wallet.getStatus()).isEqualTo(WalletStatus.CLOSED);
        assertThat(wallet.getClosureUtr()).isEqualTo("UTR123");
        assertThat(wallet.getClosedAt()).isNotNull();
        assertThat(wallet.getClosedBy()).isEqualTo("U1");
    }

    @Test
    void aZeroBalanceWalletStillCloses() {
        WalletEntry refund = service.close(T, null);

        assertThat(refund.getAmount()).isEqualByComparingTo("0");
        assertThat(wallet.getStatus()).isEqualTo(WalletStatus.CLOSED);
    }

    @Test
    void lowBalanceNotifiesOnceWhenCrossingTheThreshold() {
        fund("1500");
        wallet.setLowBalanceThreshold(d("1000"));

        service.debitOrReceivable(T, WalletEntryType.TDS_DEDUCTED, d("600"), "x1", null, null, ReceivableReason.CHARGEBACK);
        verify(notificationService, times(1)).lowBalance(eq(T), eqAmount("900"));
        assertThat(wallet.getLowBalanceNotifiedAt()).isNotNull();

        service.debitOrReceivable(T, WalletEntryType.TDS_DEDUCTED, d("100"), "x2", null, null, ReceivableReason.CHARGEBACK);
        verify(notificationService, never()).lowBalance(eq(T), eqAmount("800"));

        service.credit(T, WalletEntryType.TOPUP, d("500"), "x3", null, null, null);
        assertThat(wallet.getLowBalanceNotifiedAt()).isNull();

        service.debitOrReceivable(T, WalletEntryType.TDS_DEDUCTED, d("400"), "x4", null, null, ReceivableReason.CHARGEBACK);
        verify(notificationService, times(2)).lowBalance(eq(T), eqAmount("900"));
    }

    @Test
    void chargebacksPostedIsTheCoveredPartPlusTheReceivable() {
        when(entryRepository.sumAmountByTypeAndRef(WalletEntryType.ADJUSTMENT, "TOPUP_REQUEST", "R1")).thenReturn(new BigDecimal("-120.00"));
        when(receivableRepository.sumOriginalAmountByReasonAndRef(ReceivableReason.CHARGEBACK, "TOPUP_REQUEST", "R1"))
                .thenReturn(new BigDecimal("80.00"));
        assertThat(service.chargebacksPosted("TOPUP_REQUEST", "R1")).isEqualByComparingTo("200.00");
    }
}
