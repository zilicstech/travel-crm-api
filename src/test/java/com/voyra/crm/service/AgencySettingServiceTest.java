package com.voyra.crm.service;

import com.voyra.crm.dto.AgencySettingUpdateRequest;
import com.voyra.crm.entity.AgencySetting;
import com.voyra.crm.enums.AgencySettingKind;
import com.voyra.crm.repository.AgencySettingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** CRD-04: a rename goes through the same rules as a create, and system defaults stay put. */
@ExtendWith(MockitoExtension.class)
class AgencySettingServiceTest {

    @Mock
    private AgencySettingRepository agencySettingRepository;
    @Mock
    private VendorService vendorService;

    @InjectMocks
    private AgencySettingService agencySettingService;

    private AgencySetting row(String name, boolean isDefault) {
        AgencySetting s = AgencySetting.builder().id("S1").kind(AgencySettingKind.TRAVEL_CATEGORY)
                .name(name).isActive(true).isDefault(isDefault).sortOrder(0).build();
        when(agencySettingRepository.findById("S1")).thenReturn(Optional.of(s));
        return s;
    }

    private AgencySettingUpdateRequest rename(String name) {
        AgencySettingUpdateRequest r = new AgencySettingUpdateRequest();
        r.setName(name);
        return r;
    }

    @Test
    void renamingToAnotherLiveNameIsRefused() {
        row("Honeymoon", false);
        when(agencySettingRepository.existsByKindAndNameIgnoreCase(AgencySettingKind.TRAVEL_CATEGORY, "hotel")).thenReturn(true);

        assertThatThrownBy(() -> agencySettingService.update("S1", rename("hotel"))).isInstanceOf(IllegalStateException.class);
        verify(agencySettingRepository, never()).save(any());
    }

    @Test
    void renamingToBlankIsRefused() {
        row("Honeymoon", false);

        assertThatThrownBy(() -> agencySettingService.update("S1", rename("   "))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void changingOnlyTheCaseOfItsOwnNameIsAllowed() {
        AgencySetting s = row("honeymoon", false);

        agencySettingService.update("S1", rename(" Honeymoon "));

        assertThat(s.getName()).isEqualTo("Honeymoon");
    }

    @Test
    void aSystemDefaultCannotBeRenamedOrRemoved() {
        row("Leisure", true);

        assertThatThrownBy(() -> agencySettingService.update("S1", rename("Fun"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> agencySettingService.delete("S1")).isInstanceOf(IllegalStateException.class);
        verify(agencySettingRepository, never()).delete(any());
    }
}
