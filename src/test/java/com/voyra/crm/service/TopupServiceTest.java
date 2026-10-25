package com.voyra.crm.service;

import com.voyra.crm.dto.BankTopupCreateRequest;
import com.voyra.crm.dto.GatewayTopupCreateRequest;
import com.voyra.crm.dto.GatewayTopupResponse;
import com.voyra.crm.entity.GatewayOrder;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.dto.TopupApproveRequest;
import com.voyra.crm.dto.TopupRejectRequest;
import com.voyra.crm.dto.TopupResponse;
import com.voyra.crm.entity.TopupRequest;
import com.voyra.crm.entity.Wallet;
import com.voyra.crm.entity.WalletEntry;
import com.voyra.crm.enums.GatewayOrderPurpose;
import com.voyra.crm.enums.GatewayOrderStatus;
import com.voyra.crm.enums.TopupMode;
import com.voyra.crm.enums.TopupStatus;
import com.voyra.crm.enums.WalletEntryType;
import com.voyra.crm.enums.WalletStatus;
import com.voyra.crm.exception.FieldValidationException;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.models.GatewayOrderCreated;
import com.voyra.crm.repository.GatewayOrderRepository;
import com.voyra.crm.service.payments.PaymentGateway;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.repository.TopupRequestRepository;
import com.voyra.crm.repository.WalletRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class TopupServiceTest {

    private static final byte[] PDF = "%PDF-1.4 test".getBytes();

    @Mock
    private TopupRequestRepository topupRepository;
    @Mock
    private WalletRepository walletRepository;
    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private WalletService walletService;
    @Mock
    private FileStorageService fileStorageService;
    @Mock
    private PlatformAuditService platformAuditService;
    @Mock
    private GatewayOrderRepository gatewayOrderRepository;
    @Mock
    private PaymentGateway paymentGateway;
    @Mock
    private GatewayWebhookService webhookService;
    @InjectMocks
    private TopupService service;

    private static BankTopupCreateRequest request(String ref, byte[] bytes) {
        BankTopupCreateRequest r = new BankTopupCreateRequest();
        r.setAmount(new BigDecimal("50000.00"));
        r.setBankRef(ref);
        r.setProof(new MockMultipartFile("proof", "slip.pdf", "application/pdf", bytes));
        return r;
    }

    private static Wallet wallet(WalletStatus status) {
        return Wallet.builder().id("W1").tenantId("T1").status(status).build();
    }

    private static TopupRequest pending() {
        return TopupRequest.builder().id("R1").tenantId("T1").walletId("W1").mode(TopupMode.BANK_TRANSFER)
                .amount(new BigDecimal("50000.00")).status(TopupStatus.PENDING).bankRef("UTR1").build();
    }

    @Test
    void normalizeUpperCasesAndDropsWhitespace() {
        assertThat(TopupService.normalize(" sbin 12\t3x ")).isEqualTo("SBIN123X");
    }

    @Test
    void bankRefIsUniqueIgnoringCaseAndSpaces() {
        given(walletRepository.findByTenantId("T1")).willReturn(Optional.of(wallet(WalletStatus.OPEN)));
        given(topupRepository.existsByBankRefNormalized("SBIN1234")).willReturn(true);
        assertThatThrownBy(() -> service.createBank("T1", "U1", "Asha", request("sbin 1234", PDF)))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getField()).isEqualTo("bankRef"));
        verifyNoInteractions(fileStorageService);
    }

    @Test
    void aClosedWalletCannotRequestATopup() {
        given(walletRepository.findByTenantId("T1")).willReturn(Optional.of(wallet(WalletStatus.CLOSED)));
        assertThatThrownBy(() -> service.createBank("T1", "U1", "Asha", request("SBIN1234", PDF)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
    }

    @Test
    void proofOver5MbIsRefused() {
        given(walletRepository.findByTenantId("T1")).willReturn(Optional.of(wallet(WalletStatus.OPEN)));
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        System.arraycopy(PDF, 0, big, 0, PDF.length);
        assertThatThrownBy(() -> service.createBank("T1", "U1", "Asha", request("SBIN1234", big)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("5 MB");
        verify(topupRepository, never()).save(any());
    }

    @Test
    void createStoresProofAndSavesPending() {
        given(walletRepository.findByTenantId("T1")).willReturn(Optional.of(wallet(WalletStatus.OPEN)));
        given(fileStorageService.store(anyString(), anyString(), anyString(), any())).willReturn("k/slip.pdf");
        given(topupRepository.save(any(TopupRequest.class))).willAnswer(i -> i.getArgument(0));
        TopupResponse r = service.createBank("T1", "U1", "Asha", request("sbin 1234", PDF));
        assertThat(r.getStatus()).isEqualTo(TopupStatus.PENDING);
        assertThat(r.getBankRef()).isEqualTo("sbin 1234");
        assertThat(r.getRequestedByName()).isEqualTo("Asha");
    }

    @Test
    void approveCreditsTheApprovedAmountNotTheRequested() {
        TopupRequest t = pending();
        given(topupRepository.findByIdForUpdate("R1")).willReturn(Optional.of(t));
        WalletEntry entry = WalletEntry.builder().id("E1").amount(new BigDecimal("49500.00")).build();
        given(walletService.credit(eq("T1"), eq(WalletEntryType.TOPUP), eq(new BigDecimal("49500.00")),
                eq("topup:R1"), eq("TOPUP_REQUEST"), eq("R1"), anyString())).willReturn(entry);
        TopupApproveRequest req = new TopupApproveRequest();
        req.setApprovedAmount(new BigDecimal("49500.00"));
        TopupResponse r = service.approve("R1", "A1", req);
        assertThat(r.getStatus()).isEqualTo(TopupStatus.APPROVED);
        assertThat(r.getApprovedAmount()).isEqualByComparingTo("49500.00");
        assertThat(t.getCreditEntryId()).isEqualTo("E1");
        verify(platformAuditService).record(eq(com.voyra.crm.enums.PlatformAuditAction.TOPUP_DECISION), anyString(), eq("R1"), any());
    }

    @Test
    void approveRecordsTheCreditedAmountNotTheRequestOnAReplay() {
        TopupRequest t = pending();
        given(topupRepository.findByIdForUpdate("R1")).willReturn(Optional.of(t));
        // The idempotency key already holds a 49,500 credit; a replay asking for 40,000 returns that entry.
        given(walletService.credit(any(), any(), any(), eq("topup:R1"), any(), any(), any()))
                .willReturn(WalletEntry.builder().id("E1").amount(new BigDecimal("49500.00")).build());
        TopupApproveRequest req = new TopupApproveRequest();
        req.setApprovedAmount(new BigDecimal("40000.00"));

        TopupResponse r = service.approve("R1", "A1", req);

        assertThat(t.getApprovedAmount()).isEqualByComparingTo("49500.00");
        assertThat(r.getApprovedAmount()).isEqualByComparingTo("49500.00");
        verify(platformAuditService).record(any(), anyString(), eq("R1"), org.mockito.ArgumentMatchers.argThat(
                (Map<String, Object> m) -> "49500.00".equals(m.get("approved"))));
    }

    @Test
    void approvingTwiceIs409() {
        TopupRequest t = pending();
        t.setStatus(TopupStatus.APPROVED);
        given(topupRepository.findByIdForUpdate("R1")).willReturn(Optional.of(t));
        TopupApproveRequest req = new TopupApproveRequest();
        req.setApprovedAmount(BigDecimal.TEN);
        assertThatThrownBy(() -> service.approve("R1", "A1", req))
                .isInstanceOf(IllegalStateException.class).hasMessage("This top-up was already approved");
        verifyNoInteractions(walletService);
    }

    @Test
    void rejectNeedsANoteAndPostsNothing() {
        TopupRequest t = pending();
        given(topupRepository.findByIdForUpdate("R1")).willReturn(Optional.of(t));
        TopupRejectRequest req = new TopupRejectRequest();
        req.setNote("Not in statement");
        TopupResponse r = service.reject("R1", "A1", req);
        assertThat(r.getStatus()).isEqualTo(TopupStatus.REJECTED);
        assertThat(r.getReviewNote()).isEqualTo("Not in statement");
        verifyNoInteractions(walletService);
        t.setStatus(TopupStatus.REJECTED);
        assertThatThrownBy(() -> service.reject("R1", "A1", req)).hasMessage("This top-up was already rejected");
    }

    @Test
    void gatewayTopupRefusedOnStubForANonTestWallet() {
        given(walletRepository.findByTenantId("T1")).willReturn(Optional.of(wallet(WalletStatus.OPEN)));
        given(paymentGateway.name()).willReturn("stub");
        GatewayTopupCreateRequest req = new GatewayTopupCreateRequest();
        req.setAmount(new BigDecimal("500.00"));

        assertThatThrownBy(() -> service.createGateway("T1", "U1", "Owner", req))
                .isInstanceOf(IllegalStateException.class).hasMessage("Online top-up is not available yet");
        verify(paymentGateway, never()).createOrder(any(), any(), any());
        verifyNoInteractions(topupRepository, gatewayOrderRepository);
    }

    @Test
    void gatewayTopupOnStubWorksForATestWallet() {
        given(walletRepository.findByTenantId("T1"))
                .willReturn(Optional.of(wallet(WalletStatus.OPEN).toBuilder().isTest(true).build()));
        given(paymentGateway.name()).willReturn("stub");
        given(paymentGateway.createOrder(any(), anyString(), any(Map.class))).willReturn(new GatewayOrderCreated("order_stub_1", 50000L, "INR"));
        GatewayTopupCreateRequest req = new GatewayTopupCreateRequest();
        req.setAmount(new BigDecimal("500.00"));

        assertThat(service.createGateway("T1", "U1", "Owner", req).getGatewayOrderId()).isEqualTo("order_stub_1");
    }

    @Test
    void platformListFiltersByMode() {
        TopupRequest bank = pending();
        TopupRequest online = pending().toBuilder().id("R2").mode(TopupMode.GATEWAY).build();
        given(topupRepository.findByStatusOrderByCreatedAtDesc(TopupStatus.PENDING)).willReturn(List.of(bank, online));
        given(topupRepository.findAllByOrderByCreatedAtDesc()).willReturn(List.of(bank, online));

        assertThat(service.listForPlatform(TopupStatus.PENDING, TopupMode.BANK_TRANSFER)).extracting(TopupResponse::getId)
                .containsExactly("R1");
        assertThat(service.listForPlatform(null, null)).extracting(TopupResponse::getId).containsExactly("R1", "R2");
    }

    @Test
    void gatewayTopupCreatesAnOrderAndAPendingRequest() {
        given(walletRepository.findByTenantId("T1")).willReturn(Optional.of(wallet(WalletStatus.OPEN)));
        given(tenantRepository.findById("T1")).willReturn(Optional.of(Tenant.builder().id("T1").agencyName("Sky Trips").build()));
        given(paymentGateway.createOrder(eq(new BigDecimal("500.50")), anyString(), any(Map.class)))
                .willReturn(new GatewayOrderCreated("order_1", 50050L, "INR"));
        given(paymentGateway.publicKeyId()).willReturn("rzp_test_key");
        given(paymentGateway.name()).willReturn("razorpay");
        GatewayTopupCreateRequest req = new GatewayTopupCreateRequest();
        req.setAmount(new BigDecimal("500.50"));

        GatewayTopupResponse r = service.createGateway("T1", "U1", "Owner", req);

        ArgumentCaptor<GatewayOrder> order = ArgumentCaptor.forClass(GatewayOrder.class);
        verify(gatewayOrderRepository).save(order.capture());
        ArgumentCaptor<TopupRequest> topup = ArgumentCaptor.forClass(TopupRequest.class);
        verify(topupRepository).save(topup.capture());
        assertThat(order.getValue().getPurpose()).isEqualTo(GatewayOrderPurpose.TOPUP);
        assertThat(order.getValue().getStatus()).isEqualTo(GatewayOrderStatus.CREATED);
        assertThat(order.getValue().getRefId()).isEqualTo(topup.getValue().getId());
        assertThat(topup.getValue().getMode()).isEqualTo(TopupMode.GATEWAY);
        assertThat(topup.getValue().getStatus()).isEqualTo(TopupStatus.PENDING);
        assertThat(topup.getValue().getGatewayOrderId()).isEqualTo("order_1");
        assertThat(r.getTopupId()).isEqualTo(topup.getValue().getId());
        assertThat(r.getGatewayOrderId()).isEqualTo("order_1");
        assertThat(r.getAmountPaise()).isEqualTo(50050L);
        assertThat(r.getKeyId()).isEqualTo("rzp_test_key");
        assertThat(r.getProvider()).isEqualTo("razorpay");
        assertThat(r.getAgencyName()).isEqualTo("Sky Trips");
    }

    @Test
    void simulateIs404WhenProviderIsRazorpay() {
        org.mockito.Mockito.lenient().when(paymentGateway.name()).thenReturn("razorpay");
        assertThatThrownBy(() -> service.simulate("T1", "R1")).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(webhookService, walletService);
    }

    @Test
    void simulateIs404WhenSimulationIsSwitchedOffEvenOnTheStub() {
        org.mockito.Mockito.lenient().when(paymentGateway.name()).thenReturn("stub");
        given(paymentGateway.simulateEnabled()).willReturn(false);
        assertThatThrownBy(() -> service.simulate("T1", "R1")).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(webhookService, topupRepository);
    }

    @Test
    void simulateIs404ForAnotherAgencysTopup() {
        given(paymentGateway.simulateEnabled()).willReturn(true);
        TopupRequest t = pending().toBuilder().tenantId("T2").mode(TopupMode.GATEWAY).gatewayOrderId("order_1").build();
        given(topupRepository.findById("R1")).willReturn(Optional.of(t));
        assertThatThrownBy(() -> service.simulate("T1", "R1")).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(webhookService);
    }

    @Test
    void simulateRefusedOnANonTestWallet() {
        given(paymentGateway.simulateEnabled()).willReturn(true);
        TopupRequest t = pending().toBuilder().mode(TopupMode.GATEWAY).gatewayOrderId("order_1").build();
        given(topupRepository.findById("R1")).willReturn(Optional.of(t));
        given(walletRepository.findByTenantId("T1")).willReturn(Optional.of(wallet(WalletStatus.OPEN)));

        assertThatThrownBy(() -> service.simulate("T1", "R1"))
                .isInstanceOf(IllegalStateException.class).hasMessage("Simulated payments only work on a test wallet");
        verifyNoInteractions(webhookService);
    }

    @Test
    void simulateRunsACapturedEventThroughTheWebhookHandler() {
        given(paymentGateway.simulateEnabled()).willReturn(true);
        TopupRequest t = pending().toBuilder().mode(TopupMode.GATEWAY).gatewayOrderId("order_1").build();
        given(topupRepository.findById("R1")).willReturn(Optional.of(t));
        given(walletRepository.findByTenantId("T1")).willReturn(Optional.of(wallet(WalletStatus.OPEN).toBuilder().isTest(true).build()));

        service.simulate("T1", "R1");

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(webhookService).handle(body.capture(), any());
        assertThat(body.getValue()).contains("\"event\":\"payment.captured\"", "\"order_id\":\"order_1\"",
                "\"amount\":5000000", "\"currency\":\"INR\"");
    }
}
