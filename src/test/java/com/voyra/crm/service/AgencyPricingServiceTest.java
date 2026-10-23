package com.voyra.crm.service;

import com.voyra.crm.dto.AgencyPricingCreateRequest;
import com.voyra.crm.dto.AgencyPricingResponse;
import com.voyra.crm.dto.PricingPreviewResponse;
import com.voyra.crm.entity.AgencyPricing;
import com.voyra.crm.enums.DiscountBase;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.models.ReferenceFare;
import com.voyra.crm.repository.AgencyPricingRepository;
import com.voyra.crm.repository.TenantRepository;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgencyPricingServiceTest {

    private static final String T = "T1";

    @Mock
    private AgencyPricingRepository repository;
    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private PlatformSettingService settings;
    @Mock
    private PlatformAuditService auditService;
    @InjectMocks
    private AgencyPricingService service;

    @BeforeEach
    void setUp() {
        lenient().when(tenantRepository.existsById(T)).thenReturn(true);
        lenient().when(settings.discountBase()).thenReturn(DiscountBase.GROSS);
        lenient().when(settings.commissionEstimatePct()).thenReturn(new BigDecimal("5"));
        lenient().when(settings.referenceFareBasket()).thenReturn(List.of(
                new ReferenceFare("Domestic LCC", new BigDecimal("5000"), new BigDecimal("3500")),
                new ReferenceFare("Domestic FSC", new BigDecimal("9000"), new BigDecimal("6000")),
                new ReferenceFare("International", new BigDecimal("45000"), new BigDecimal("22000"))));
        lenient().when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        loginAs("U1");
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void loginAs(String userId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal(userId, "admin", UserType.SUPER_ADMIN, null), null));
    }

    private static AgencyPricingCreateRequest request(String d, String c, Instant from) {
        AgencyPricingCreateRequest r = new AgencyPricingCreateRequest();
        r.setProduct(PricingProduct.FLIGHT);
        r.setDiscountPct(new BigDecimal(d));
        r.setCommissionPct(new BigDecimal(c));
        r.setEffectiveFrom(from);
        return r;
    }

    private static AgencyPricing row(String id, Instant from, boolean approved) {
        return AgencyPricing.builder().id(id).tenantId(T).product(PricingProduct.FLIGHT)
                .discountPct(BigDecimal.ONE).commissionPct(BigDecimal.ONE).effectiveFrom(from)
                .worstCaseNetPer10k(BigDecimal.ZERO).requiresSecondApproval(!approved).createdBy("U1")
                .createdAt(from).approvedBy(approved ? "U1" : null).approvedAt(approved ? from : null).build();
    }

    @Test
    void createRefusesAPastEffectiveDate() {
        assertThatThrownBy(() -> service.create(T, request("0", "0", Instant.now().minus(1, ChronoUnit.HOURS))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Effective date must be now or later");
        verify(repository, never()).save(any());
    }

    @Test
    void createRefusesAnUnknownAgency() {
        assertThatThrownBy(() -> service.create("nope", request("0", "0", Instant.now())))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Agency not found: nope");
    }

    @Test
    void createRefusesADuplicateEffectiveDate() {
        Instant from = Instant.now().plus(1, ChronoUnit.DAYS);
        when(repository.existsByTenantIdAndProductAndEffectiveFrom(T, PricingProduct.FLIGHT, from)).thenReturn(true);
        assertThatThrownBy(() -> service.create(T, request("0", "0", from))).isInstanceOf(IllegalStateException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void createAutoApprovesAProfitableRow() {
        AgencyPricingResponse r = service.create(T, request("0", "0", Instant.now()));
        assertThat(r.getWorstCaseNetPer10k()).isEqualByComparingTo("244.44");
        assertThat(r.isRequiresSecondApproval()).isFalse();
        assertThat(r.getApprovedAt()).isNotNull().isEqualTo(r.getCreatedAt());
        assertThat(r.getApprovedBy()).isEqualTo("U1").isEqualTo(r.getCreatedBy());
        verify(auditService).record(eq(PlatformAuditAction.PRICING_CREATE), eq("AGENCY_PRICING"), anyString(), anyMap());
    }

    @Test
    void createLeavesALossMakingRowUnapproved() {
        AgencyPricingResponse r = service.create(T, request("10", "4", Instant.now()));
        assertThat(r.getWorstCaseNetPer10k()).isNegative();
        assertThat(r.isRequiresSecondApproval()).isTrue();
        assertThat(r.getApprovedAt()).isNull();
        assertThat(r.getApprovedBy()).isNull();
    }

    @Test
    void createNeedsSecondApprovalWhenCommissionMayExceedEarned() {
        AgencyPricingCreateRequest req = request("0", "0", Instant.now());
        req.setAllowCommissionAboveEarned(true);
        AgencyPricingResponse r = service.create(T, req);
        assertThat(r.getWorstCaseNetPer10k()).isNotNegative();
        assertThat(r.isRequiresSecondApproval()).isTrue();
        assertThat(r.getApprovedAt()).isNull();
    }

    @Test
    void approveNeedsADifferentAdmin() {
        when(repository.findById("P1")).thenReturn(Optional.of(row("P1", Instant.now(), false)));
        assertThatThrownBy(() -> service.approve(T, "P1"))
                .isInstanceOf(IllegalStateException.class).hasMessage("A different platform admin must approve a loss-making rate");
        verify(repository, never()).save(any());
    }

    @Test
    void approveByASecondAdminStampsAndAudits() {
        when(repository.findById("P1")).thenReturn(Optional.of(row("P1", Instant.now(), false)));
        loginAs("U2");
        AgencyPricingResponse r = service.approve(T, "P1");
        assertThat(r.getApprovedBy()).isEqualTo("U2");
        assertThat(r.getApprovedAt()).isNotNull();
        verify(auditService).record(eq(PlatformAuditAction.PRICING_APPROVE), eq("AGENCY_PRICING"), eq("P1"), anyMap());
    }

    @Test
    void approveRefusesAnApprovedRowAndAnotherAgencysRow() {
        when(repository.findById("P1")).thenReturn(Optional.of(row("P1", Instant.now(), true)));
        loginAs("U2");
        assertThatThrownBy(() -> service.approve(T, "P1"))
                .isInstanceOf(IllegalStateException.class).hasMessage("This rate is already approved");
        assertThatThrownBy(() -> service.approve("T2", "P1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolveIgnoresFutureAndUnapprovedRows() {
        Instant now = Instant.now();
        AgencyPricing past = row("A", now.minus(2, ChronoUnit.DAYS), true);
        when(repository.findByTenantIdAndProductOrderByEffectiveFromDesc(T, PricingProduct.FLIGHT)).thenReturn(List.of(
                row("C", now.plus(1, ChronoUnit.DAYS), true), row("B", now.minus(1, ChronoUnit.DAYS), false), past));
        assertThat(service.resolve(T, PricingProduct.FLIGHT, now)).containsSame(past);
    }

    @Test
    void resolveIsEmptyWhenNothingIsConfigured() {
        assertThat(service.resolve(T, PricingProduct.HOTEL, Instant.now())).isEmpty();
    }

    @Test
    void historyMarksOnlyTheResolvedRowInForce() {
        Instant now = Instant.now();
        when(repository.findByTenantIdOrderByEffectiveFromDesc(T)).thenReturn(List.of(
                row("C", now.plus(1, ChronoUnit.DAYS), true), row("B", now.minus(1, ChronoUnit.DAYS), false),
                row("A", now.minus(2, ChronoUnit.DAYS), true), row("Z", now.minus(3, ChronoUnit.DAYS), true)));
        List<AgencyPricingResponse> h = service.history(T, null);
        assertThat(h).extracting(AgencyPricingResponse::getId).containsExactly("C", "B", "A", "Z");
        assertThat(h).extracting(AgencyPricingResponse::isInForce).containsExactly(false, false, true, false);
    }

    @Test
    void previewFlagsALossMakingRate() {
        PricingPreviewResponse p = service.preview(T, PricingProduct.FLIGHT, new BigDecimal("3"), new BigDecimal("4"), false);
        assertThat(p.getWorstCaseNetPer10k()).isEqualByComparingTo("-251.11");
        assertThat(p.isRequiresSecondApproval()).isTrue();
        assertThat(p.getBasketLines()).hasSize(3);
        assertThat(p.getBasketLines().get(2).getVoyraNet()).isEqualByComparingTo("-1130.00");
        assertThat(p.getBasketLines().get(2).getDisplayed()).isEqualByComparingTo("43650.00");
    }
}
