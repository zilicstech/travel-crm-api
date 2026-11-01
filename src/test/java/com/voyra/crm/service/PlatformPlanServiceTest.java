package com.voyra.crm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.dto.PlanPricesRequest;
import com.voyra.crm.dto.PlanPricesResponse;
import com.voyra.crm.entity.PlatformSetting;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.models.PlanPrices;
import com.voyra.crm.repository.PlatformSettingRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlatformPlanServiceTest {

    private static final ObjectMapper MAPPER = Jackson2ObjectMapperBuilder.json().build();

    @Mock
    private PlatformSettingService settings;
    @Mock
    private PlatformSettingRepository repository;
    @Mock
    private PlatformAuditService auditService;

    private PlatformPlanService service() {
        return new PlatformPlanService(settings, repository, auditService, MAPPER);
    }

    @BeforeEach
    void actor() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("U1", "admin", UserType.SUPER_ADMIN, null), null));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static PlanPricesRequest request() {
        return new PlanPricesRequest(new BigDecimal("3499"), new BigDecimal("5999"), new BigDecimal("449"));
    }

    private static PlatformSetting row(String value) {
        return PlatformSetting.builder().key("sku_prices").value(value).build();
    }

    @Test
    void setMergesTheThreePricesKeepsOtherKeysAndAuditsOnce() throws Exception {
        when(settings.planPrices()).thenReturn(new PlanPrices(new BigDecimal("2999"), new BigDecimal("4999"), new BigDecimal("399")));
        when(repository.findById("sku_prices")).thenReturn(Optional.of(row("{\"CRM_CORE\":2499,\"STAFF_SEATS\":399,\"SUITE\":4999,\"LITE\":2999}")));
        when(repository.save(any(PlatformSetting.class))).thenAnswer(i -> i.getArgument(0));

        PlanPricesResponse res = service().set(request());

        ArgumentCaptor<PlatformSetting> saved = ArgumentCaptor.forClass(PlatformSetting.class);
        verify(repository).save(saved.capture());
        JsonNode json = MAPPER.readTree(saved.getValue().getValue());
        assertThat(json.get("CRM_CORE").decimalValue()).isEqualByComparingTo("2499");
        assertThat(json.get("LITE").decimalValue()).isEqualByComparingTo("3499");
        assertThat(json.get("SUITE").decimalValue()).isEqualByComparingTo("5999");
        assertThat(json.get("STAFF_SEATS").decimalValue()).isEqualByComparingTo("449");
        assertThat(saved.getValue().getUpdatedBy()).isEqualTo("U1");
        assertThat(saved.getValue().getUpdatedAt()).isNotNull();
        verify(auditService).record(PlatformAuditAction.PLAN_PRICES_SET, "PLATFORM_SETTING", "sku_prices", Map.of(
                "from", Map.of("LITE", "2999.00", "SUITE", "4999.00", "STAFF_SEATS", "399.00"),
                "to", Map.of("LITE", "3499.00", "SUITE", "5999.00", "STAFF_SEATS", "449.00")));
        verify(auditService, never()).record(eq(PlatformAuditAction.SETTING_SET), any(), any(), any());
        assertThat(res.getLite()).isEqualByComparingTo("3499");
        assertThat(res.getSuite()).isEqualByComparingTo("5999");
        assertThat(res.getStaffSeats()).isEqualByComparingTo("449");
    }

    @Test
    void setWithNoStoredRowWritesTheThreePrices() throws Exception {
        when(settings.planPrices()).thenReturn(new PlanPrices(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE));
        when(repository.findById("sku_prices")).thenReturn(Optional.empty());
        when(repository.save(any(PlatformSetting.class))).thenAnswer(i -> i.getArgument(0));

        service().set(request());

        ArgumentCaptor<PlatformSetting> saved = ArgumentCaptor.forClass(PlatformSetting.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getKey()).isEqualTo("sku_prices");
        JsonNode json = MAPPER.readTree(saved.getValue().getValue());
        assertThat(json.size()).isEqualTo(3);
        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder("LITE", "SUITE", "STAFF_SEATS");
    }

    @Test
    void anUnreadableStoredValueFailsLoudly() {
        when(settings.planPrices()).thenReturn(new PlanPrices(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE));
        when(repository.findById("sku_prices")).thenReturn(Optional.of(row("{oops")));

        assertThatThrownBy(() -> service().set(request()))
                .isInstanceOf(IllegalStateException.class).hasMessage("sku_prices is not valid JSON");
        verify(repository, never()).save(any());
    }

    @Test
    void getReturnsThePlansTabPrices() {
        when(settings.planPrices()).thenReturn(new PlanPrices(new BigDecimal("2999"), new BigDecimal("4999"), new BigDecimal("399")));

        PlanPricesResponse res = service().get();

        assertThat(res.getLite()).isEqualByComparingTo("2999");
        assertThat(res.getSuite()).isEqualByComparingTo("4999");
        assertThat(res.getStaffSeats()).isEqualByComparingTo("399");
    }
}
