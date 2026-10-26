package com.voyra.crm.service;

import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.dto.WalletAdjustRequest;
import com.voyra.crm.dto.WalletEntryResponse;
import com.voyra.crm.dto.WalletSummaryResponse;
import com.voyra.crm.entity.PlatformReceivable;
import com.voyra.crm.entity.Wallet;
import com.voyra.crm.entity.WalletEntry;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.ReceivableReason;
import com.voyra.crm.enums.ReceivableStatus;
import com.voyra.crm.enums.TopupStatus;
import com.voyra.crm.enums.WalletEntryType;
import com.voyra.crm.enums.WalletStatus;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.repository.PlatformReceivableRepository;
import com.voyra.crm.repository.TopupRequestRepository;
import com.voyra.crm.repository.WalletEntryRepository;
import com.voyra.crm.repository.WalletRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WalletQueryServiceTest {

    @Mock
    private WalletRepository walletRepository;
    @Mock
    private WalletEntryRepository entryRepository;
    @Mock
    private PlatformReceivableRepository receivableRepository;
    @Mock
    private TopupRequestRepository topupRequestRepository;
    @Mock
    private WalletService walletService;
    @Mock
    private PlatformAuditService auditService;
    @Mock
    private com.voyra.crm.repository.PlatformBookingRepository platformBookingRepository;
    @InjectMocks
    private WalletQueryService service;

    private Wallet wallet() {
        return Wallet.builder().id("W1").tenantId("T1").balance(new BigDecimal("1000.00")).held(new BigDecimal("300.00"))
                .status(WalletStatus.OPEN).createdAt(Instant.now()).build();
    }

    private WalletEntry entry(String id, String type, String narration) {
        return WalletEntry.builder().id(id).walletId("W1").tenantId("T1").type(WalletEntryType.valueOf(type))
                .amount(new BigDecimal("100.00")).balanceAfter(new BigDecimal("1000.00")).heldAfter(new BigDecimal("300.00"))
                .narration(narration).createdByName("Asha").createdAt(Instant.parse("2026-10-25T10:00:00Z")).build();
    }

    @Test
    void summaryListsOnlyDueReceivablesAndCountsPendingTopups() {
        given(walletRepository.findByTenantId("T1")).willReturn(Optional.of(wallet()));
        PlatformReceivable due = PlatformReceivable.builder().id("R1").tenantId("T1").amount(new BigDecimal("40.00"))
                .originalAmount(new BigDecimal("50.00")).reason(ReceivableReason.LATE_TICKET).status(ReceivableStatus.DUE)
                .createdAt(Instant.now()).build();
        given(receivableRepository.findByTenantIdAndStatusOrderByCreatedAt("T1", ReceivableStatus.DUE)).willReturn(List.of(due));
        given(topupRequestRepository.countByWalletIdAndStatus("W1", TopupStatus.PENDING)).willReturn(2L);

        WalletSummaryResponse s = service.summary("T1");

        assertThat(s.getAvailable()).isEqualByComparingTo("700.00");
        assertThat(s.getDueReceivables()).hasSize(1);
        assertThat(s.getDueReceivables().get(0).getAmount()).isEqualByComparingTo("40.00");
        assertThat(s.getDueReceivables().get(0).getReasonText()).isEqualTo("a ticket issued after its hold was released"); // R16
        assertThat(s.getPendingTopups()).isEqualTo(2);
        assertThat(s.getCurrency()).isEqualTo("INR");
        assertThat(s.getStatus()).isEqualTo("OPEN");
    }

    @Test
    void missingWalletIsNotFound() {
        given(walletRepository.findByTenantId("T9")).willReturn(Optional.empty());
        assertThatThrownBy(() -> service.summary("T9")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void entriesCarryAvailableAfterAsBalanceMinusHeld() {
        given(walletRepository.findByTenantId("T1")).willReturn(Optional.of(wallet()));
        Pageable p = PageRequest.of(0, 25);
        given(entryRepository.findPagedByWalletIdOrderByCreatedAtDescIdDesc("W1", p))
                .willReturn(new PageImpl<>(List.of(entry("E1", "TOPUP", "x")), p, 1));

        PagedResponse<WalletEntryResponse> page = service.entries("T1", p);

        assertThat(page.getContent().get(0).getAvailableAfter()).isEqualByComparingTo("700.00");
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    /** R19: booking rows posted without a narration read in words, with the PNR where the booking has one. */
    @Test
    void bookingEntriesWithoutANarrationGetAPlainOne() {
        given(walletRepository.findByTenantId("T1")).willReturn(Optional.of(wallet()));
        Pageable p = PageRequest.of(0, 25);
        java.util.function.BiFunction<String, String, WalletEntry> pb = (type, ref) -> {
            WalletEntry e = entry("E-" + type + ref, type, null);
            e.setRefType("PLATFORM_BOOKING");
            e.setRefId(ref);
            return e;
        };
        WalletEntry topup = entry("E9", "TOPUP", null);
        given(entryRepository.findPagedByWalletIdOrderByCreatedAtDescIdDesc("W1", p)).willReturn(new PageImpl<>(List.of(
                pb.apply("HOLD", "PB1"), pb.apply("HOLD_RELEASE", "PB1"), pb.apply("BOOKING_DEBIT", "PB1"),
                pb.apply("COMMISSION_RECALL", "PB2"), pb.apply("HOLD", "PB3"), topup), p, 6));
        given(platformBookingRepository.findAllById(any())).willReturn(List.of(
                com.voyra.crm.entity.PlatformBooking.builder().id("PB1").product(com.voyra.crm.enums.PricingProduct.FLIGHT).pnr("MKK4EK").build(),
                com.voyra.crm.entity.PlatformBooking.builder().id("PB2").product(com.voyra.crm.enums.PricingProduct.HOTEL).pnr("MH82RJ").build(),
                com.voyra.crm.entity.PlatformBooking.builder().id("PB3").product(com.voyra.crm.enums.PricingProduct.FLIGHT).build()));

        List<String> narrations = service.entries("T1", p).getContent().stream().map(WalletEntryResponse::getNarration).toList();

        assertThat(narrations).containsExactly("Held for flight booking MKK4EK", "Hold released for flight booking MKK4EK",
                "Paid for flight booking MKK4EK", "Commission taken back on cancelled hotel booking MH82RJ",
                "Held for flight booking", null);
    }

    @Test
    void csvHasTheHeaderAndNeutralisesFormulaNarration() {
        given(walletRepository.findByTenantId("T1")).willReturn(Optional.of(wallet()));
        given(entryRepository.findByWalletIdOrderByCreatedAtDescIdDesc(eq("W1"), any(Pageable.class)))
                .willReturn(List.of(entry("E1", "TOPUP", "=HYPERLINK(\"x\")")));

        String csv = service.csv("T1");

        String[] lines = csv.split("\n");
        assertThat(lines[0]).isEqualTo("Date,Type,Amount,Balance,Held,Available,Narration,By");
        assertThat(lines[1]).startsWith("2026-10-25T10:00:00Z,TOPUP,100.00,1000.00,300.00,700.00,");
        assertThat(lines[1]).contains("'=HYPERLINK");
    }

    @Test
    void thresholdIsSetUnderTheRowLockAndRearmsTheAlert() {
        Wallet w = wallet();
        w.setLowBalanceNotifiedAt(Instant.now());
        given(walletRepository.findByTenantIdForUpdate("T1")).willReturn(Optional.of(w));
        given(walletRepository.save(w)).willReturn(w);

        service.setThreshold("T1", new BigDecimal("5000"));

        assertThat(w.getLowBalanceThreshold()).isEqualByComparingTo("5000");
        assertThat(w.getLowBalanceNotifiedAt()).isNull();
    }

    @Test
    void adjustKeysOnTheTenantAndRequestIdAndIsAudited() {
        WalletAdjustRequest req = new WalletAdjustRequest();
        req.setAmount(new BigDecimal("-25.00"));
        req.setReason("Typo fix");
        req.setRequestId("req-12345678");
        WalletEntry posted = entry("E1", "ADJUSTMENT", "Typo fix");
        given(walletService.adjust("T1", req.getAmount(), "Typo fix", "adj:T1:req-12345678")).willReturn(posted);

        WalletEntryResponse r = service.adjust("T1", req);

        assertThat(r.getId()).isEqualTo("E1");
        verify(auditService).record(eq(PlatformAuditAction.WALLET_ADJUST), eq("Wallet"), eq("W1"), any(Map.class));
    }

    @Test
    void closeAndTestFlagAreAudited() {
        given(walletService.close("T1", "UTR12345")).willReturn(entry("E2", "CLOSURE_REFUND", "c"));
        service.close("T1", "UTR12345");
        verify(auditService).record(eq(PlatformAuditAction.WALLET_CLOSE), eq("Wallet"), eq("W1"), any(Map.class));

        Wallet w = wallet();
        given(walletRepository.findByTenantIdForUpdate("T1")).willReturn(Optional.of(w));
        service.setTest("T1", true);
        assertThat(w.getIsTest()).isTrue();
        verify(auditService).record(eq(PlatformAuditAction.WALLET_TEST_FLAG), eq("Wallet"), eq("W1"), any(Map.class));
    }
}
