package com.voyra.crm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.enums.DiscountBase;
import com.voyra.crm.repository.PlatformSettingRepository;
import com.voyra.crm.entity.PlatformSetting;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.Map;
import java.util.Optional;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class PlatformSettingServiceTest {

    @Mock
    private PlatformSettingRepository repository;
    @Mock
    private PlatformAuditService auditService;

    private static final ObjectMapper MAPPER = Jackson2ObjectMapperBuilder.json().build();

    private PlatformSettingService service() {
        return new PlatformSettingService(repository, auditService, MAPPER);
    }

    @Test
    void discountBaseDefaultsToGross() {
        assertThat(service().discountBase()).isEqualTo(DiscountBase.GROSS);
        assertThat(service().referenceFareBasket()).hasSize(3);
    }

    @Test
    void setRefusesAnUnknownKey() {
        assertThatThrownBy(() -> service().set("nope", "1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Unknown setting: nope");
    }

    @Test
    void setValidatesTdsRateRange() {
        assertThatThrownBy(() -> service().set("tds_rate", "31"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tds_rate");
    }

    @Test
    void basketParsesAndRejectsBaseAboveGross() {
        assertThatThrownBy(() -> service().set("reference_fare_basket", "[{\"label\":\"x\",\"gross\":100,\"base\":200}]"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reference_fare_basket");
        assertThatThrownBy(() -> service().set("reference_fare_basket", "[]"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void bad(String key, String value) {
        assertThatThrownBy(() -> service().set(key, value))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(key);
    }

    @Test
    void basketRejectsNullJson() {
        bad("reference_fare_basket", "null");
    }

    @Test
    void basketRejectsANullFare() {
        bad("reference_fare_basket", "[null]");
    }

    @Test
    void basketRejectsBlankLabelMalformedJsonAndOverlongValue() {
        bad("reference_fare_basket", "[{\"label\":\" \",\"gross\":100,\"base\":50}]");
        bad("reference_fare_basket", "{not json");
        bad("reference_fare_basket", "x".repeat(4001));
    }

    @Test
    void scalarSettingsRejectBadValues() {
        bad("discount_base", "NET");
        bad("commission_estimate_pct", "101");
        bad("tds_rate", "abc");
    }

    @Test
    void setStoresTrimmedValueStampsActorAndAudits() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("U1", "admin", UserType.SUPER_ADMIN, null), null));
        when(repository.findById("tds_rate")).thenReturn(Optional.empty());
        when(repository.save(any(PlatformSetting.class))).thenAnswer(i -> i.getArgument(0));

        var res = service().set("tds_rate", " 2 ");

        ArgumentCaptor<PlatformSetting> saved = ArgumentCaptor.forClass(PlatformSetting.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getValue()).isEqualTo("2");
        assertThat(saved.getValue().getUpdatedBy()).isEqualTo("U1");
        assertThat(res.getValue()).isEqualTo("2");
        verify(auditService).record(eq(PlatformAuditAction.SETTING_SET), eq("PLATFORM_SETTING"), eq("tds_rate"),
                eq(Map.of("key", "tds_rate", "from", "0", "to", "2")));
    }
}
