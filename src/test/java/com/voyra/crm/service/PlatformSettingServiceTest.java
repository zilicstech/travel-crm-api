package com.voyra.crm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.repository.PlatformSettingRepository;
import com.voyra.crm.entity.PlatformSetting;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    @ParameterizedTest
    @ValueSource(strings = {"discount_base", "commission_estimate_pct", "reference_fare_basket", "reference_fare_basket_hotel"})
    void retiredKeysAreUnknown(String key) {
        assertThatThrownBy(() -> service().set(key, "1")).isInstanceOf(IllegalArgumentException.class).hasMessage("Unknown setting: " + key);
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

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void bad(String key, String value) {
        assertThatThrownBy(() -> service().set(key, value)).isInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> assertThat(e.getMessage()).contains(key));
    }

    @Test
    void scalarSettingsRejectBadValues() {
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

    @Test
    void skuPricesAndFoundingAgenciesDefaultToTheSpec() {
        Map<String, java.math.BigDecimal> prices = service().skuPrices();
        assertThat(prices).containsOnlyKeys("CRM_CORE", "STAFF_SEATS", "ACCOUNTS_GST", "REPORTS", "VISA", "SUITE", "LITE");
        assertThat(prices.get("SUITE")).isEqualByComparingTo("4999");
        assertThat(prices.get("STAFF_SEATS")).isEqualByComparingTo("399");
        assertThat(service().foundingAgencies()).containsExactly("Voyra Global");
    }

    @Test
    void skuPricesMustNameEveryPriceWithANonNegativeNumber() {
        signIn();
        assertThatThrownBy(() -> service().set("sku_prices", "{\"SUITE\":4999}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sku_prices");
        assertThatThrownBy(() -> service().set("sku_prices", "not json"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sku_prices");
        String negative = "{\"CRM_CORE\":-1,\"STAFF_SEATS\":399,\"ACCOUNTS_GST\":1999,\"REPORTS\":699,\"VISA\":499,\"SUITE\":4999,\"LITE\":2999}";
        assertThatThrownBy(() -> service().set("sku_prices", negative))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sku_prices");
        String extra = "{\"CRM_CORE\":1,\"STAFF_SEATS\":399,\"ACCOUNTS_GST\":1999,\"REPORTS\":699,\"VISA\":499,\"SUITE\":4999,\"X\":1}";
        assertThatThrownBy(() -> service().set("sku_prices", extra))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sku_prices");

        when(repository.save(any(PlatformSetting.class))).thenAnswer(i -> i.getArgument(0));
        String ok = "{\"CRM_CORE\":2999,\"STAFF_SEATS\":399,\"ACCOUNTS_GST\":1999,\"REPORTS\":699,\"VISA\":499,\"SUITE\":5499,\"LITE\":2999}";
        assertThat(service().set("sku_prices", ok).getValue()).isEqualTo(ok);
    }

    @Test
    void foundingAgenciesMustBeAJsonListOfNames() {
        signIn();
        assertThatThrownBy(() -> service().set("founding_agencies", "Voyra Global"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("founding_agencies");
        assertThatThrownBy(() -> service().set("founding_agencies", "[\"\"]"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("founding_agencies");

        when(repository.save(any(PlatformSetting.class))).thenAnswer(i -> i.getArgument(0));
        assertThat(service().set("founding_agencies", "[]").getValue()).isEqualTo("[]");
        assertThat(service().set("founding_agencies", "[\"Voyra Global\",\"Sky Trips\"]").getValue())
                .isEqualTo("[\"Voyra Global\",\"Sky Trips\"]");
    }

    private void signIn() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("U1", "admin", UserType.SUPER_ADMIN, null), null));
    }

    @Test
    void voyraLegalSettingsAreValidatedAndMayBeEmpty() {
        bad("voyra_gstin", "27AAACV1234F1Z");
        bad("voyra_gstin", "AAACV1234F1Z527");
        bad("voyra_state_code", "7");
        bad("voyra_state_code", "MH");
        bad("subscription_sac", "");
        bad("subscription_sac", "abc");
        bad("voyra_legal_name", "x".repeat(201));
        bad("voyra_address", "x".repeat(501));
        assertThat(service().voyraLegal()).isEqualTo(new com.voyra.crm.models.VoyraLegal(null, null, null, null));
        assertThat(service().subscriptionSac()).isEqualTo("998314");
    }

    /** R14: voyra_state_code can be cleared (blank means unset); every other key still refuses a blank value. */
    @Test
    void onlyTheVoyraStateCodeMayBeCleared() {
        signIn();
        when(repository.save(any(PlatformSetting.class))).thenAnswer(i -> i.getArgument(0));
        assertThat(service().set("voyra_state_code", "  ").getValue()).isEmpty();
        assertThat(service().set("voyra_state_code", "27").getValue()).isEqualTo("27");
        for (String key : java.util.List.of("tds_rate", "voyra_legal_name", "voyra_address", "voyra_gstin")) {
            assertThatThrownBy(() -> service().set(key, " ")).as(key)
                    .isInstanceOf(IllegalArgumentException.class).hasMessage(key + " must not be blank");
        }
    }

    @Test
    void voyraStateFallsBackToItsGstin() {
        org.mockito.Mockito.lenient().when(repository.findById("voyra_gstin")).thenReturn(Optional.of(PlatformSetting.builder().key("voyra_gstin").value("27AAACV1234F1Z5").build()));
        org.mockito.Mockito.lenient().when(repository.findById("voyra_legal_name")).thenReturn(Optional.of(PlatformSetting.builder().key("voyra_legal_name").value("Voyra Travel Pvt Ltd").build()));

        com.voyra.crm.models.VoyraLegal legal = service().voyraLegal();

        assertThat(legal.stateCode()).isEqualTo("27");
        assertThat(legal.legalName()).isEqualTo("Voyra Travel Pvt Ltd");
    }

    /** The live switch is gone: suppliers are switched per row now, so the old key is refused like any unknown one. */
    @Test
    void tripjackLiveReadyIsAnUnknownSetting() {
        assertThatThrownBy(() -> service().set("tripjack_live_ready", "true"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Unknown setting: tripjack_live_ready");
    }
}
