package com.voyra.crm.config;

import com.voyra.crm.entity.AgencySetting;
import com.voyra.crm.enums.AgencySettingKind;
import com.voyra.crm.repository.AgencySettingRepository;
import com.voyra.crm.repository.TenantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgencySettingSeedRunnerTest {

    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private AgencySettingRepository agencySettingRepository;
    @InjectMocks
    private AgencySettingSeedRunner runner;

    @Test
    void seedsEveryKindIntoATenantWithNoSettings() {
        when(agencySettingRepository.existsByKind(any())).thenReturn(false);
        when(agencySettingRepository.findByKindAndServiceTypeOrderBySortOrderAsc(any(), any())).thenReturn(List.of());

        runner.seedCurrentTenant();

        ArgumentCaptor<AgencySetting> saved = ArgumentCaptor.forClass(AgencySetting.class);
        verify(agencySettingRepository, atLeastOnce()).save(saved.capture());
        Set<AgencySettingKind> kinds = saved.getAllValues().stream().map(AgencySetting::getKind).collect(Collectors.toSet());
        assertEquals(Set.of(AgencySettingKind.TRAVEL_CATEGORY, AgencySettingKind.DOCUMENT_TYPE,
                AgencySettingKind.LEAD_SOURCE, AgencySettingKind.SERVICE_PREFERENCE), kinds);
    }

    @Test
    void doesNotTouchKindsTheAgencyAlreadyHas() {
        when(agencySettingRepository.existsByKind(any())).thenReturn(true);
        when(agencySettingRepository.findByKindAndServiceTypeOrderBySortOrderAsc(any(), any()))
                .thenReturn(List.of(AgencySetting.builder().build()));

        runner.seedCurrentTenant();

        verify(agencySettingRepository, never()).save(any());
    }
}
