package com.voyra.crm.service;

import com.voyra.crm.dto.SubscriptionInvoiceResponse;
import com.voyra.crm.entity.Subscription;
import com.voyra.crm.entity.SubscriptionInvoice;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.models.VoyraLegal;
import com.voyra.crm.enums.BillingCycle;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.Sku;
import com.voyra.crm.enums.SubscriptionPlan;
import com.voyra.crm.enums.SubscriptionStatus;
import com.voyra.crm.enums.WaivedReason;
import com.voyra.crm.repository.SubscriptionInvoiceRepository;
import com.voyra.crm.repository.SubscriptionRepository;
import com.voyra.crm.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SubscriptionBillingServiceTest {

    private static final Map<String, BigDecimal> PRICES = Map.of("CRM_CORE", new BigDecimal("2499"), "STAFF_SEATS",
            new BigDecimal("399"), "ACCOUNTS_GST", new BigDecimal("1999"), "REPORTS", new BigDecimal("699"), "VISA",
            new BigDecimal("499"), "SUITE", new BigDecimal("4999"));

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private SubscriptionInvoiceRepository invoiceRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private PlatformSettingService settingService;
    @Mock private EntitlementService entitlementService;
    @Mock private PlatformAuditService auditService;
    @Mock private NotificationService notificationService;
    @Mock private FileStorageService fileStorageService;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private PlatformTransactionManager transactionManager;

    private SubscriptionBillingService service;

    @BeforeEach
    void setUp() {
        service = new SubscriptionBillingService(subscriptionRepository, invoiceRepository, tenantRepository, settingService,
                entitlementService, auditService, notificationService, fileStorageService, jdbcTemplate, transactionManager);
        lenient().when(settingService.skuPrices()).thenReturn(PRICES);
        lenient().when(settingService.voyraLegal()).thenReturn(new VoyraLegal("Voyra Travel Pvt Ltd", "27AAACV1234F1Z5", "Andheri, Mumbai", "27"));
        lenient().when(settingService.subscriptionSac()).thenReturn("998314");
        lenient().when(invoiceRepository.save(any(SubscriptionInvoice.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(42L);
    }

    private static Subscription suite(int extraSeats, BillingCycle cycle) {
        return Subscription.builder().id("S1").tenantId("T1").plan(SubscriptionPlan.SUITE)
                .skus(EnumSet.allOf(Sku.class).stream().map(Enum::name).toList()).seats(extraSeats)
                .status(SubscriptionStatus.ACTIVE).billingCycle(cycle).updatedAt(Instant.now()).build();
    }

    private static BigDecimal amount(Map<String, Object> line) {
        return new BigDecimal((String) line.get("amount"));
    }

    private SubscriptionInvoice issued(String status) {
        SubscriptionInvoice inv = SubscriptionInvoice.builder().id("I1").tenantId("T1").number("VOY-SUB-2026-1")
                .periodStart(Instant.parse("2026-11-01T00:00:00Z")).periodEnd(Instant.parse("2026-12-01T00:00:00Z"))
                .lines(List.of()).amount(new BigDecimal("4999.00")).gst(new BigDecimal("899.82"))
                .total(new BigDecimal("5898.82")).status(status).dueAt(Instant.parse("2026-11-08T00:00:00Z"))
                .createdAt(Instant.now()).build();
        lenient().when(invoiceRepository.findByIdForUpdate("I1")).thenReturn(Optional.of(inv));
        lenient().when(invoiceRepository.findById("I1")).thenReturn(Optional.of(inv));
        return inv;
    }

    // ---- arithmetic

    @Test
    void suiteMonthlyIsTheSuitePricePlusExtraSeatsWithGstAt18Percent() {
        Subscription s = suite(2, BillingCycle.MONTHLY);
        List<Map<String, Object>> lines = SubscriptionBillingService.lines(s, PRICES);

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).containsEntry("sku", "SUITE").containsEntry("quantity", 1).containsEntry("months", 1);
        assertThat(amount(lines.get(0))).isEqualByComparingTo("4999");
        assertThat(lines.get(1)).containsEntry("sku", "STAFF_SEATS").containsEntry("quantity", 2);
        assertThat(amount(lines.get(1))).isEqualByComparingTo("798");

        SubscriptionInvoice inv = service.issue(s, Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"));
        assertThat(inv.getAmount()).isEqualByComparingTo("5797.00");
        assertThat(inv.getGst()).isEqualByComparingTo("1043.46");   // 5797 x 0.18
        assertThat(inv.getTotal()).isEqualByComparingTo("6840.46");
        assertThat(inv.getStatus()).isEqualTo("ISSUED");
    }

    @Test
    void annualIsTenMonthlyPricesForTwelveMonths() {
        Subscription s = suite(0, BillingCycle.ANNUAL).toBuilder().plan(SubscriptionPlan.CUSTOM)
                .skus(List.of("CRM_CORE", "STAFF_SEATS", "VISA")).seats(3).build();
        Instant start = Instant.parse("2026-11-01T00:00:00Z");

        SubscriptionInvoice inv = service.issue(s, start, start);

        // (2499 + 3 x 399 + 499) x 10 = 41950
        assertThat(inv.getAmount()).isEqualByComparingTo("41950.00");
        assertThat(inv.getGst()).isEqualByComparingTo("7551.00");
        assertThat(inv.getTotal()).isEqualByComparingTo("49501.00");
        assertThat(inv.getLines()).allSatisfy(l -> assertThat(l).containsEntry("months", 10));
        assertThat(inv.getPeriodEnd()).isEqualTo(start.atZone(ZoneId.of("Asia/Kolkata")).plusMonths(12).toInstant());
    }

    @Test
    void gstRoundsHalfUpToPaise() {
        assertThat(SubscriptionBillingService.gst(new BigDecimal("699.00"))).isEqualByComparingTo("125.82");
        assertThat(SubscriptionBillingService.gst(new BigDecimal("0.25"))).isEqualByComparingTo("0.05"); // 0.045 -> 0.05
    }

    @Test
    void customWithoutSkusHasNothingToBill() {
        Subscription s = suite(0, BillingCycle.MONTHLY).toBuilder().plan(SubscriptionPlan.CUSTOM).skus(List.of()).build();
        assertThatThrownBy(() -> service.issue(s, Instant.now(), Instant.now())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void numberComesFromTheVoyraSequenceAndDueIsSevenDaysAfterThePeriodStarts() {
        Instant start = Instant.parse("2026-11-01T00:00:00Z");
        SubscriptionInvoice inv = service.issue(suite(0, BillingCycle.MONTHLY), start, start);

        assertThat(inv.getNumber()).isEqualTo("VOY-SUB-2026-42");
        assertThat(inv.getDueAt()).isEqualTo(start.plus(Duration.ofDays(7)));
        verify(jdbcTemplate).queryForObject(contains("voyra_invoice_sequence"), eq(Long.class), eq("VOY-SUB"), eq(2026));
    }

    // ---- raise now

    @Test
    void foundingAgencyIsNeverInvoiced() {
        Subscription s = suite(0, BillingCycle.MONTHLY).toBuilder().waivedReason(WaivedReason.FOUNDING_AGENCY).build();
        given(subscriptionRepository.findByTenantIdForUpdate("T1")).willReturn(Optional.of(s));

        assertThatThrownBy(() -> service.raiseNow("T1")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("waived");
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void raiseNowDuringATrialStartsThePaidPeriodWhenTheTrialEndsAndIsAudited() {
        Instant trialEnd = Instant.now().plus(Duration.ofDays(5));
        Subscription s = suite(0, BillingCycle.MONTHLY).toBuilder().status(SubscriptionStatus.TRIAL).trialEndsAt(trialEnd)
                .periodEnd(trialEnd).build();
        given(subscriptionRepository.findByTenantIdForUpdate("T1")).willReturn(Optional.of(s));

        SubscriptionInvoiceResponse r = service.raiseNow("T1");

        assertThat(r.getPeriodStart()).isEqualTo(trialEnd);
        verify(auditService).record(eq(PlatformAuditAction.SUBSCRIPTION_INVOICE_RAISE), eq("SUBSCRIPTION_INVOICE"), anyString(), any());
    }

    @Test
    void raiseNowRefusesASecondOpenInvoiceForThePeriod() {
        Subscription s = suite(0, BillingCycle.MONTHLY).toBuilder().periodEnd(Instant.now().plus(Duration.ofDays(3))).build();
        given(subscriptionRepository.findByTenantIdForUpdate("T1")).willReturn(Optional.of(s));
        given(invoiceRepository.existsByTenantIdAndPeriodStartAndStatusIn(eq("T1"), eq(s.getPeriodEnd()), any())).willReturn(true);

        assertThatThrownBy(() -> service.raiseNow("T1")).isInstanceOf(IllegalStateException.class);
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void renewalIsRaisedOncePerPeriodEvenAfterAVoid() {
        Subscription s = suite(0, BillingCycle.MONTHLY).toBuilder().periodEnd(Instant.parse("2026-11-01T00:00:00Z")).build();
        given(invoiceRepository.existsByTenantIdAndPeriodStart("T1", s.getPeriodEnd())).willReturn(true);

        assertThat(service.raiseRenewal(s, Instant.parse("2026-11-01T01:00:00Z"))).isEmpty();
        verify(invoiceRepository, never()).save(any());
    }

    // ---- paying (Review Focus 4)

    @Test
    void gatewayPaymentClearsTheWaiverMovesToActiveWithTheNextPeriodAndRefreshes() {
        SubscriptionInvoice inv = issued("ISSUED");
        Subscription s = suite(0, BillingCycle.MONTHLY).toBuilder().status(SubscriptionStatus.READ_ONLY)
                .waivedReason(WaivedReason.COMPLIMENTARY).periodEnd(Instant.parse("2026-10-01T00:00:00Z")).build();
        given(subscriptionRepository.findByTenantIdForUpdate("T1")).willReturn(Optional.of(s));

        assertThat(service.markPaid("I1", "pay_1")).isTrue();

        assertThat(inv.getStatus()).isEqualTo("PAID");
        assertThat(inv.getPaidVia()).isEqualTo("GATEWAY");
        assertThat(inv.getPaidRef()).isEqualTo("pay_1");
        assertThat(inv.getPaidAt()).isNotNull();
        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(s.getWaivedReason()).isNull();
        assertThat(s.getPeriodStart()).isEqualTo(inv.getPeriodStart());
        assertThat(s.getPeriodEnd()).isEqualTo(inv.getPeriodEnd());
        verify(subscriptionRepository).save(s);
        verify(entitlementService).refresh("T1");
    }

    @Test
    void theSamePaymentTwicePaysOnceAndRaisesNoAlert() {
        issued("ISSUED");
        given(subscriptionRepository.findByTenantIdForUpdate("T1")).willReturn(Optional.of(suite(0, BillingCycle.MONTHLY)));

        assertThat(service.markPaid("I1", "pay_1")).isTrue();
        assertThat(service.markPaid("I1", "pay_1")).isFalse();

        verify(invoiceRepository, times(1)).save(any());
        verify(entitlementService, times(1)).refresh("T1");
        verifyNoInteractions(notificationService);
    }

    @Test
    void aDifferentPaymentOnAPaidInvoiceAlertsAndChangesNothing() {
        SubscriptionInvoice inv = issued("PAID");
        inv.setPaidVia("BANK");
        inv.setPaidRef("UTR123");

        assertThat(service.markPaid("I1", "pay_2")).isFalse();

        assertThat(inv.getPaidRef()).isEqualTo("UTR123");
        verify(notificationService).platformAlert(anyString(), contains("pay_2"));
        verify(invoiceRepository, never()).save(any());
        verifyNoInteractions(entitlementService);
    }

    @Test
    void aCaptureOnAVoidInvoiceAlertsAndChangesNothing() {
        SubscriptionInvoice inv = issued("VOID");

        assertThat(service.markPaid("I1", "pay_1")).isFalse();

        assertThat(inv.getStatus()).isEqualTo("VOID");
        verify(notificationService).platformAlert(anyString(), contains("pay_1"));
        verifyNoInteractions(entitlementService);
    }

    @Test
    void aLaterPaidPeriodIsNeverMovedBackwards() {
        issued("ISSUED");
        Instant later = Instant.parse("2027-01-01T00:00:00Z");
        Subscription s = suite(0, BillingCycle.MONTHLY).toBuilder().periodStart(Instant.parse("2026-12-01T00:00:00Z"))
                .periodEnd(later).build();
        given(subscriptionRepository.findByTenantIdForUpdate("T1")).willReturn(Optional.of(s));

        service.markPaid("I1", "pay_1");

        assertThat(s.getPeriodEnd()).isEqualTo(later);
        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    void bankMarkPaidIsAuditedAndRecordsTheReference() {
        SubscriptionInvoice inv = issued("ISSUED");
        given(subscriptionRepository.findByTenantIdForUpdate("T1")).willReturn(Optional.of(suite(0, BillingCycle.MONTHLY)));

        SubscriptionInvoiceResponse r = service.markPaidByBank("T1", "I1", "  UTR 999 ");

        assertThat(r.getStatus()).isEqualTo("PAID");
        assertThat(inv.getPaidVia()).isEqualTo("BANK");
        assertThat(inv.getPaidRef()).isEqualTo("UTR 999");
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass((Class) Map.class);
        verify(auditService).record(eq(PlatformAuditAction.SUBSCRIPTION_INVOICE_PAID), eq("SUBSCRIPTION_INVOICE"), eq("I1"), detail.capture());
        assertThat(detail.getValue()).containsEntry("reference", "UTR 999");
        verify(entitlementService).refresh("T1");
    }

    @Test
    void anotherAgencysInvoiceIsNotFound() {
        issued("ISSUED");
        assertThatThrownBy(() -> service.markPaidByBank("T2", "I1", "UTR")).isInstanceOf(com.voyra.crm.exception.NotFoundException.class);
        assertThatThrownBy(() -> service.voidInvoice("T2", "I1", "wrong")).isInstanceOf(com.voyra.crm.exception.NotFoundException.class);
    }

    @Test
    void voidOnlyAnUnpaidInvoiceAndAPastDueAgencyWithNothingElseOverdueIsActiveAgain() {
        SubscriptionInvoice inv = issued("ISSUED");
        Subscription s = suite(0, BillingCycle.MONTHLY).toBuilder().status(SubscriptionStatus.PAST_DUE).build();
        given(subscriptionRepository.findByTenantIdForUpdate("T1")).willReturn(Optional.of(s));

        service.voidInvoice("T1", "I1", "Plan changed");

        assertThat(inv.getStatus()).isEqualTo("VOID");
        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        verify(auditService).record(eq(PlatformAuditAction.SUBSCRIPTION_INVOICE_VOID), eq("SUBSCRIPTION_INVOICE"), eq("I1"), any());
        verify(entitlementService).refresh("T1");

        inv.setStatus("PAID");
        assertThatThrownBy(() -> service.voidInvoice("T1", "I1", "again")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void pdfIsRenderedAndStoredOnceThenServedFromStorage() {
        SubscriptionInvoice inv = issued("ISSUED");
        inv.setLines(SubscriptionBillingService.lines(suite(1, BillingCycle.MONTHLY), PRICES));
        given(fileStorageService.store(eq("platform"), eq("subscription-invoices/T1"), eq("I1"), any())).willReturn("key1");

        SubscriptionBillingService.InvoicePdf pdf = service.pdf("T1", "I1");

        assertThat(pdf.fileName()).isEqualTo("VOY-SUB-2026-1.pdf");
        verify(invoiceRepository).setPdfKey("I1", "key1");

        service.pdf("T1", "I1");
        verify(fileStorageService).retrieve("key1");
        verify(fileStorageService, times(1)).store(anyString(), anyString(), anyString(), any());
        assertThatThrownBy(() -> service.pdf("T2", "I1")).isInstanceOf(com.voyra.crm.exception.NotFoundException.class);
    }

    // ---- review fixes

    @Test
    void raiseNowIsRefusedWhileAnInvoiceIsOpen() {
        Subscription s = suite(0, BillingCycle.MONTHLY).toBuilder().status(SubscriptionStatus.READ_ONLY)
                .periodEnd(Instant.now().minus(Duration.ofDays(20))).build();
        given(subscriptionRepository.findByTenantIdForUpdate("T1")).willReturn(Optional.of(s));
        given(invoiceRepository.findFirstByTenantIdAndStatusOrderByDueAtAsc("T1", "ISSUED"))
                .willReturn(Optional.of(SubscriptionInvoice.builder().id("I0").number("VOY-SUB-2026-7").status("ISSUED").build()));

        assertThatThrownBy(() -> service.raiseNow("T1")).isInstanceOf(IllegalStateException.class)
                .hasMessage("Void or settle invoice VOY-SUB-2026-7 first");
        verify(invoiceRepository, never()).save(any());
    }

    private static Tenant agency(String stateCode, String gstin) {
        return Tenant.builder().id("T1").agencyName("Sky Trips").legalName("Sky Trips LLP").gstNumber(gstin)
                .address("MG Road, Pune").stateCode(stateCode).build();
    }

    @Test
    void sameStateAsVoyraSplitsCgstAndSgstAndSnapshotsBothParties() {
        given(tenantRepository.findById("T1")).willReturn(Optional.of(agency("27", "27ABCDE1234F1Z5")));
        Instant start = Instant.parse("2026-11-01T00:00:00Z");

        SubscriptionInvoice inv = service.issue(suite(0, BillingCycle.MONTHLY), start, start);

        assertThat(inv.getCgst()).isEqualByComparingTo("449.91");  // 4999 x 9 %
        assertThat(inv.getSgst()).isEqualByComparingTo("449.91");
        assertThat(inv.getIgst()).isEqualByComparingTo("0");
        assertThat(inv.getGst()).isEqualByComparingTo("899.82");
        assertThat(inv.getTotal()).isEqualByComparingTo("5898.82");
        assertThat(inv.getSacCode()).isEqualTo("998314");
        @SuppressWarnings("unchecked")
        Map<String, Object> seller = (Map<String, Object>) inv.getBillingDetails().get("seller");
        @SuppressWarnings("unchecked")
        Map<String, Object> buyer = (Map<String, Object>) inv.getBillingDetails().get("buyer");
        assertThat(seller).containsEntry("gstin", "27AAACV1234F1Z5").containsEntry("stateCode", "27");
        assertThat(buyer).containsEntry("name", "Sky Trips LLP").containsEntry("gstin", "27ABCDE1234F1Z5")
                .containsEntry("address", "MG Road, Pune").containsEntry("stateCode", "27");
        assertThat(SubscriptionBillingService.toResponse(inv).getCgst()).isEqualByComparingTo("449.91");
    }

    @Test
    void anotherStateIsIgstAt18Percent() {
        given(tenantRepository.findById("T1")).willReturn(Optional.of(agency("29", null)));
        Instant start = Instant.parse("2026-11-01T00:00:00Z");

        SubscriptionInvoice inv = service.issue(suite(0, BillingCycle.MONTHLY), start, start);

        assertThat(inv.getIgst()).isEqualByComparingTo("899.82");
        assertThat(inv.getCgst()).isEqualByComparingTo("0");
        assertThat(inv.getSgst()).isEqualByComparingTo("0");
        assertThat(inv.getGst()).isEqualByComparingTo("899.82");
    }

    @Test
    void agencyStateFallsBackToItsGstinAndUnknownStateIsIgst() {
        given(tenantRepository.findById("T1")).willReturn(Optional.of(agency(null, "27ABCDE1234F1Z5")));
        Instant start = Instant.parse("2026-11-01T00:00:00Z");
        assertThat(service.issue(suite(0, BillingCycle.MONTHLY), start, start).getCgst()).isEqualByComparingTo("449.91");

        given(tenantRepository.findById("T1")).willReturn(Optional.of(agency(null, null)));
        assertThat(service.issue(suite(0, BillingCycle.MONTHLY), start, start).getIgst()).isEqualByComparingTo("899.82");
    }

    @Test
    void settlingReRendersAndReStoresThePdf() {
        SubscriptionInvoice inv = issued("ISSUED");
        inv.setPdfKey("old");
        given(subscriptionRepository.findByTenantIdForUpdate("T1")).willReturn(Optional.of(suite(0, BillingCycle.MONTHLY)));
        given(fileStorageService.store(eq("platform"), eq("subscription-invoices/T1"), eq("I1"), any())).willReturn("paid-copy");

        service.markPaid("I1", "pay_1");

        verify(invoiceRepository).setPdfKey("I1", "paid-copy");
        verify(fileStorageService).delete("old");
    }

    @Test
    void voidingReRendersAndReStoresThePdf() {
        issued("ISSUED");
        given(subscriptionRepository.findByTenantIdForUpdate("T1")).willReturn(Optional.of(suite(0, BillingCycle.MONTHLY)));
        given(fileStorageService.store(eq("platform"), eq("subscription-invoices/T1"), eq("I1"), any())).willReturn("void-copy");

        service.voidInvoice("T1", "I1", "Plan changed");

        verify(invoiceRepository).setPdfKey("I1", "void-copy");
    }

    @Test
    void storageFailureWhenServingReturnsFreshlyRenderedBytes() throws Exception {
        SubscriptionInvoice inv = issued("PAID");
        inv.setPdfKey("gone");
        inv.setPaidAt(Instant.now());
        given(fileStorageService.retrieve("gone")).willThrow(new IllegalStateException("bucket down"));
        given(fileStorageService.store(any(), any(), any(), any())).willThrow(new IllegalStateException("bucket down"));

        SubscriptionBillingService.InvoicePdf pdf = service.pdf("T1", "I1");

        assertThat(new String(pdf.resource().getInputStream().readNBytes(4))).isEqualTo("%PDF");
    }

    @Test
    void payingSetsAPeriodStartWhenThereWasNone() {
        SubscriptionInvoice inv = issued("ISSUED");
        Subscription s = suite(0, BillingCycle.MONTHLY).toBuilder().periodStart(null)
                .periodEnd(Instant.parse("2027-06-01T00:00:00Z")).build();
        given(subscriptionRepository.findByTenantIdForUpdate("T1")).willReturn(Optional.of(s));

        service.markPaid("I1", "pay_1");

        assertThat(s.getPeriodStart()).isNotNull();
        assertThat(s.getPeriodEnd()).isNotNull();
    }
}
