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
        assertThat(service().referenceFareBasket(com.voyra.crm.enums.PricingProduct.FLIGHT)).hasSize(3);
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
    void basketRejectsAZeroGrossFare() {
        assertThatThrownBy(() -> service().set("reference_fare_basket", "[{\"label\":\"Z\",\"gross\":0,\"base\":0}]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("reference_fare_basket needs at least one fare, each with a label and 0 <= base <= gross, gross above 0");
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

    @Test
    void skuPricesAndFoundingAgenciesDefaultToTheSpec() {
        Map<String, java.math.BigDecimal> prices = service().skuPrices();
        assertThat(prices).containsOnlyKeys("CRM_CORE", "STAFF_SEATS", "ACCOUNTS_GST", "REPORTS", "VISA", "SUITE");
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
        String negative = "{\"CRM_CORE\":-1,\"STAFF_SEATS\":399,\"ACCOUNTS_GST\":1999,\"REPORTS\":699,\"VISA\":499,\"SUITE\":4999}";
        assertThatThrownBy(() -> service().set("sku_prices", negative))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sku_prices");
        String extra = "{\"CRM_CORE\":1,\"STAFF_SEATS\":399,\"ACCOUNTS_GST\":1999,\"REPORTS\":699,\"VISA\":499,\"SUITE\":4999,\"X\":1}";
        assertThatThrownBy(() -> service().set("sku_prices", extra))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sku_prices");

        when(repository.save(any(PlatformSetting.class))).thenAnswer(i -> i.getArgument(0));
        String ok = "{\"CRM_CORE\":2999,\"STAFF_SEATS\":399,\"ACCOUNTS_GST\":1999,\"REPORTS\":699,\"VISA\":499,\"SUITE\":5499}";
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

    @Test
    void voyraStateFallsBackToItsGstin() {
        org.mockito.Mockito.lenient().when(repository.findById("voyra_gstin")).thenReturn(Optional.of(PlatformSetting.builder().key("voyra_gstin").value("27AAACV1234F1Z5").build()));
        org.mockito.Mockito.lenient().when(repository.findById("voyra_legal_name")).thenReturn(Optional.of(PlatformSetting.builder().key("voyra_legal_name").value("Voyra Travel Pvt Ltd").build()));

        com.voyra.crm.models.VoyraLegal legal = service().voyraLegal();

        assertThat(legal.stateCode()).isEqualTo("27");
        assertThat(legal.legalName()).isEqualTo("Voyra Travel Pvt Ltd");
    }

    /** Slice 4 minor 3: hotel rates are guarded against a hotel basket, flights keep theirs. */
    @Test
    void hotelPricingHasItsOwnReferenceBasket() {
        var flight = service().referenceFareBasket(com.voyra.crm.enums.PricingProduct.FLIGHT);
        var hotel = service().referenceFareBasket(com.voyra.crm.enums.PricingProduct.HOTEL);
        assertThat(hotel).isNotEmpty().isNotEqualTo(flight);
        assertThat(hotel).allSatisfy(f -> assertThat(f.base()).isLessThanOrEqualTo(f.gross()));
        when(repository.findById("reference_fare_basket_hotel"))
                .thenReturn(Optional.of(PlatformSetting.builder().key("reference_fare_basket_hotel").value("[{\"label\":\"Goa 3*\",\"gross\":4720,\"base\":4000}]").build()));
        assertThat(service().referenceFareBasket(com.voyra.crm.enums.PricingProduct.HOTEL)).singleElement()
                .satisfies(f -> assertThat(f.label()).isEqualTo("Goa 3*"));
        bad("reference_fare_basket_hotel", "[{\"label\":\"Z\",\"gross\":100,\"base\":200}]");
    }

    /** Review Focus 3: the live switch is off by default and can never be turned on through a plain setting write. */
    @Test
    void tripjackLiveReadyIsOffByDefaultAndOnlyASecondAdminTurnsItOn() {
        assertThat(service().tripjackLiveReady()).isFalse();
        assertThatThrownBy(() -> service().set("tripjack_live_ready", "true"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("second platform admin");
        bad("tripjack_live_ready", "yes");
        when(repository.findById("tripjack_live_ready"))
                .thenReturn(Optional.of(PlatformSetting.builder().key("tripjack_live_ready").value("true").build()));
        assertThat(service().tripjackLiveReady()).isTrue();
    }

    @Test
    void tripjackLiveReadyCanAlwaysBeTurnedOffAndIsAudited() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("U1", "admin", UserType.SUPER_ADMIN, null), null));
        when(repository.save(any(PlatformSetting.class))).thenAnswer(i -> i.getArgument(0));

        service().set("tripjack_live_ready", "false");

        verify(auditService).record(eq(PlatformAuditAction.SETTING_SET), eq("PLATFORM_SETTING"), eq("tripjack_live_ready"), any());
    }
}
