package com.voyra.crm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.dto.PlatformSettingResponse;
import com.voyra.crm.entity.PlatformSetting;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.models.VoyraLegal;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlatformSettingServiceCoverageTest {

    @Mock PlatformSettingRepository repository;
    @Mock PlatformAuditService auditService;
    PlatformSettingService service;

    private static final ObjectMapper MAPPER = Jackson2ObjectMapperBuilder.json().build();

    @BeforeEach
    void setUp() {
        service = new PlatformSettingService(repository, auditService, MAPPER);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("SA1", "admin", UserType.SUPER_ADMIN, null), null, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private final Map<String, String> values = new java.util.HashMap<>();

    /** Every key reads from {@link #values}; unset keys fall back to their defaults. */
    private void stored(String key, String value) {
        if (values.isEmpty()) {
            when(repository.findById(anyString())).thenAnswer(i -> Optional.ofNullable(values.get(i.<String>getArgument(0)))
                    .map(v -> PlatformSetting.builder().key(i.getArgument(0)).value(v).build()));
        }
        values.put(key, value);
    }

    private void saves() {
        when(repository.save(any(PlatformSetting.class))).thenAnswer(i -> i.getArgument(0));
    }

    private void bad(String key, String value, String message) {
        assertThatThrownBy(() -> service.set(key, value)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(message);
    }

    @Test
    void listsStoredSettingsByKey() {
        when(repository.findAll()).thenReturn(List.of(
                PlatformSetting.builder().key("tds_rate").value("2").build(),
                PlatformSetting.builder().key("discount_base").value("BASE").build()));

        assertThat(service.list()).extracting(PlatformSettingResponse::getKey).containsExactly("discount_base", "tds_rate");
    }

    @Test
    void readersFallBackToDefaultsOrUseStoredValues() {
        stored("voyra_cancel_fee", "150");
        stored("tripjack_live_ready", "true");

        assertThat(service.voyraCancelFee()).isEqualByComparingTo("150");
        assertThat(service.tdsRate()).isEqualByComparingTo("0");
        assertThat(service.paxRetentionDays()).isEqualTo(90);
        assertThat(service.tripjackLiveReady()).isTrue();
        assertThat(service.subscriptionSac()).isEqualTo("998314");
        assertThat(service.referenceFareBasket(PricingProduct.HOTEL)).extracting(f -> f.label())
                .containsExactly("Budget 3*", "Mid 4*", "Premium 5*");
        assertThat(service.skuPrices()).containsKeys("CRM_CORE", "SUITE", "LITE");
        assertThat(service.foundingAgencies()).containsExactly("Voyra Global");
    }

    @Test
    void aStoredBasketThatIsNotJsonIsReportedAsSuch() {
        stored("reference_fare_basket", "{oops");

        assertThatThrownBy(() -> service.referenceFareBasket(PricingProduct.FLIGHT))
                .hasMessage("The reference fare basket is not valid JSON");
    }

    @Test
    void voyrasLegalIdentityTrimsBlanksAndTakesTheStateFromTheGstin() {
        stored("voyra_gstin", " 27aaacv1234f1z5 ");
        stored("voyra_legal_name", "Voyra Global Pvt Ltd");

        VoyraLegal fromGstin = service.voyraLegal();

        assertThat(fromGstin.gstin()).isEqualTo("27AAACV1234F1Z5");
        assertThat(fromGstin.stateCode()).isEqualTo("27");
        assertThat(fromGstin.legalName()).isEqualTo("Voyra Global Pvt Ltd");
        assertThat(fromGstin.address()).isNull();
    }

    @Test
    void anExplicitStateWinsAndNoGstinMeansNoState() {
        stored("voyra_state_code", "29");
        VoyraLegal withState = service.voyraLegal();
        assertThat(withState.stateCode()).isEqualTo("29");
        assertThat(withState.gstin()).isNull();

        values.remove("voyra_state_code");
        assertThat(service.voyraLegal().stateCode()).isNull();
    }

    @Test
    void theStartupGstinCheckNeverFails() {
        assertThatCode(service::warnIfVoyraGstinMissing).doesNotThrowAnyException();
        stored("voyra_gstin", "27AAACV1234F1Z5");
        assertThatCode(service::warnIfVoyraGstinMissing).doesNotThrowAnyException();
        when(repository.findById("voyra_gstin")).thenThrow(new IllegalStateException("db down"));
        assertThatCode(service::warnIfVoyraGstinMissing).doesNotThrowAnyException();
        verify(repository, org.mockito.Mockito.times(3)).findById("voyra_gstin");
    }

    @Test
    void settingAValueUpdatesTheRowAndAuditsFromAndTo() {
        stored("discount_base", "GROSS");
        saves();

        PlatformSettingResponse res = service.set("discount_base", "  BASE ");

        assertThat(res.getValue()).isEqualTo("BASE");
        assertThat(res.getUpdatedAt()).isNotNull();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq(PlatformAuditAction.SETTING_SET), eq("PLATFORM_SETTING"), eq("discount_base"), detail.capture());
        assertThat(detail.getValue()).containsEntry("from", "GROSS").containsEntry("to", "BASE");
    }

    @Test
    void blankOverlongAndNullValuesAreRefusedButABlankStateCodeUnsetsIt() {
        bad("tds_rate", null, "tds_rate must not be blank");
        bad("tds_rate", "1".repeat(4001), "at most 4000 characters");
        saves();

        assertThat(service.set("voyra_state_code", " ").getValue()).isEmpty();
        verify(repository).save(any());
    }

    @Test
    void eachSimpleSettingValidatesItsShape() {
        bad("discount_base", "NET", "must be GROSS or BASE");
        bad("tds_rate", "abc", "must be a number between 0 and 30");
        bad("tds_rate", "-1", "must be between 0 and 30");
        bad("commission_estimate_pct", "101", "between 0 and 100");
        bad("voyra_cancel_fee", "1000001", "between 0 and 1000000");
        bad("pax_retention_days", "0", "1 or more");
        bad("pax_retention_days", "ten", "1 or more");
        bad("voyra_legal_name", "x".repeat(201), "at most 200 characters");
        bad("voyra_address", "x".repeat(501), "at most 500 characters");
        bad("voyra_gstin", "NOTAGSTIN", "15-character GSTIN");
        bad("voyra_state_code", "2x", "2-digit GST state code");
        bad("subscription_sac", "12", "4-8 digit SAC");
        bad("tripjack_live_ready", "yes", "true or false");
        verify(repository, never()).save(any());
    }

    @Test
    void validValuesOfEverySimpleSettingAreSaved() {
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        saves();

        for (String[] kv : List.of(
                new String[]{"discount_base", "GROSS"}, new String[]{"tds_rate", "2"}, new String[]{"commission_estimate_pct", "50"},
                new String[]{"voyra_cancel_fee", "100"}, new String[]{"pax_retention_days", "30"},
                new String[]{"voyra_legal_name", "Voyra"}, new String[]{"voyra_address", "Mumbai"},
                new String[]{"voyra_gstin", "27AAACV1234F1Z5"}, new String[]{"voyra_state_code", "27"},
                new String[]{"subscription_sac", "998314"}, new String[]{"tripjack_live_ready", "false"}, new String[]{"tripjack_live_ready", "true"},
                new String[]{"founding_agencies", "[\"Voyra Global\",\"QA Agency\"]"},
                new String[]{"sku_prices", "{\"CRM_CORE\":1,\"STAFF_SEATS\":1,\"ACCOUNTS_GST\":1,\"REPORTS\":1,\"VISA\":1,\"SUITE\":1,\"LITE\":0}"},
                new String[]{"reference_fare_basket_hotel", "[{\"label\":\"x\",\"gross\":100,\"base\":0}]"})) {
            assertThat(service.set(kv[0], kv[1]).getValue()).isEqualTo(kv[1]);
        }
    }

    @Test
    void aGstinAndStateCodeMustAgree() {
        stored("voyra_state_code", "29");
        stored("voyra_gstin", "27AAACV1234F1Z5");

        bad("voyra_gstin", "27AAACV1234F1Z5", "registered in state 27, but the GST state is 29");
        bad("voyra_state_code", "29", "registered in state 27, but the GST state is 29");
    }

    @Test
    void skuPricesNeedEveryKeyEachInRange() {
        String all = "\"CRM_CORE\":1,\"STAFF_SEATS\":1,\"ACCOUNTS_GST\":1,\"REPORTS\":1,\"VISA\":1,\"SUITE\":1";
        bad("sku_prices", "{oops", "is not valid JSON of the expected shape");
        bad("sku_prices", "null", "needs exactly");
        bad("sku_prices", "{" + all + "}", "needs exactly");
        bad("sku_prices", "{" + all + ",\"LITE\":-1}", "needs exactly");
        bad("sku_prices", "{" + all + ",\"LITE\":1000001}", "needs exactly");
        bad("sku_prices", "{" + all + ",\"LITE\":null}", "needs exactly");
    }

    @Test
    void foundingAgenciesMustBeAListOfNames() {
        bad("founding_agencies", "null", "JSON list of agency names");
        bad("founding_agencies", "[null]", "JSON list of agency names");
        bad("founding_agencies", "[\" \"]", "JSON list of agency names");
        bad("founding_agencies", "[\"" + "x".repeat(151) + "\"]", "JSON list of agency names");
    }

    @Test
    void aBasketNeedsLabelledFaresWithBaseWithinGross() {
        String hotel = "The hotel reference fare basket";
        bad("reference_fare_basket_hotel", "{oops", hotel + " must be a JSON list of fares");
        bad("reference_fare_basket_hotel", "null", hotel + " needs at least one fare");
        bad("reference_fare_basket", "[null]", "needs at least one fare");
        bad("reference_fare_basket", "[{\"gross\":100,\"base\":1}]", "needs at least one fare");
        bad("reference_fare_basket", "[{\"label\":\" \",\"gross\":100,\"base\":1}]", "needs at least one fare");
        bad("reference_fare_basket", "[{\"label\":\"x\",\"base\":1}]", "needs at least one fare");
        bad("reference_fare_basket", "[{\"label\":\"x\",\"gross\":100}]", "needs at least one fare");
        bad("reference_fare_basket", "[{\"label\":\"x\",\"gross\":100,\"base\":-1}]", "needs at least one fare");
    }
}
