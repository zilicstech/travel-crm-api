package com.voyra.crm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.enums.DiscountBase;
import com.voyra.crm.repository.PlatformSettingRepository;
import org.junit.jupiter.api.Test;
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

    private PlatformSettingService service() {
        return new PlatformSettingService(repository, auditService, new ObjectMapper());
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
}
