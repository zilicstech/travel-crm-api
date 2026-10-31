package com.voyra.crm.service;

import com.voyra.crm.dto.AgencySettingCreateRequest;
import com.voyra.crm.dto.AgencySettingResponse;
import com.voyra.crm.dto.AgencySettingUpdateRequest;
import com.voyra.crm.entity.AgencySetting;
import com.voyra.crm.enums.AgencySettingKind;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.repository.AgencySettingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Create, list, update and delete paths of {@link AgencySettingService} the rename tests leave open. */
@ExtendWith(MockitoExtension.class)
class AgencySettingServiceCoverageTest {

    @Mock private AgencySettingRepository repo;
    @Mock private VendorService vendorService;
    @InjectMocks private AgencySettingService service;

    private static AgencySettingCreateRequest create(AgencySettingKind kind, ServiceType type, String name) {
        AgencySettingCreateRequest r = new AgencySettingCreateRequest();
        r.setKind(kind);
        r.setServiceType(type);
        r.setName(name);
        return r;
    }

    private static AgencySetting row(String id, AgencySettingKind kind, ServiceType type, String name, int sort, boolean isDefault) {
        return AgencySetting.builder().id(id).kind(kind).serviceType(type).name(name).isActive(true)
                .isDefault(isDefault).sortOrder(sort).build();
    }

    @Test
    void suppliersAreNoLongerWrittenHere() {
        assertThatThrownBy(() -> service.create(create(AgencySettingKind.SUPPLIER, null, "Air India")))
                .isInstanceOf(IllegalStateException.class).hasMessage("Suppliers are now managed at /api/vendors");
        when(repo.findById("S1")).thenReturn(Optional.of(row("S1", AgencySettingKind.SUPPLIER, null, "x", 0, false)));
        assertThatThrownBy(() -> service.update("S1", new AgencySettingUpdateRequest()))
                .isInstanceOf(IllegalStateException.class).hasMessage("Suppliers are now managed at /api/vendors");
        assertThatThrownBy(() -> service.delete("S1"))
                .isInstanceOf(IllegalStateException.class).hasMessage("Suppliers are now managed at /api/vendors");
        verify(repo, never()).save(any());
        verify(repo, never()).delete(any());
    }

    @Test
    void aServicePreferenceNeedsItsServiceType() {
        assertThatThrownBy(() -> service.create(create(AgencySettingKind.SERVICE_PREFERENCE, null, "Window")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("serviceType is required for a SERVICE_PREFERENCE setting");
        verifyNoInteractions(repo);
    }

    @Test
    void aServicePreferenceIsTrimmedCheckedWithinItsTypeAndSortedLast() {
        when(repo.existsByKindAndServiceTypeAndNameIgnoreCase(AgencySettingKind.SERVICE_PREFERENCE, ServiceType.FLIGHT, "Window"))
                .thenReturn(false);
        when(repo.findByKindAndServiceTypeOrderBySortOrderAsc(AgencySettingKind.SERVICE_PREFERENCE, ServiceType.FLIGHT))
                .thenReturn(List.of(row("a", AgencySettingKind.SERVICE_PREFERENCE, ServiceType.FLIGHT, "Aisle", 4, true),
                        row("b", AgencySettingKind.SERVICE_PREFERENCE, ServiceType.FLIGHT, "Meal", 2, false)));

        AgencySettingResponse out = service.create(create(AgencySettingKind.SERVICE_PREFERENCE, ServiceType.FLIGHT, "  Window "));

        assertThat(out.getName()).isEqualTo("Window");
        assertThat(out.getSortOrder()).isEqualTo(5);
        assertThat(out.getIsActive()).isTrue();
        assertThat(out.getIsDefault()).isFalse();
        assertThat(out.getServiceType()).isEqualTo(ServiceType.FLIGHT);
        verify(repo).save(any(AgencySetting.class));
    }

    @Test
    void aFirstLeadSourceStartsAtZeroAndADuplicateIsRefused() {
        when(repo.existsByKindAndNameIgnoreCase(AgencySettingKind.LEAD_SOURCE, "Referral")).thenReturn(false);
        when(repo.findByKindOrderBySortOrderAsc(AgencySettingKind.LEAD_SOURCE)).thenReturn(List.of());
        assertThat(service.create(create(AgencySettingKind.LEAD_SOURCE, null, "Referral")).getSortOrder()).isZero();

        when(repo.existsByKindAndNameIgnoreCase(AgencySettingKind.LEAD_SOURCE, "Walk-in")).thenReturn(true);
        assertThatThrownBy(() -> service.create(create(AgencySettingKind.LEAD_SOURCE, null, "Walk-in")))
                .isInstanceOf(IllegalStateException.class).hasMessage("\"Walk-in\" already exists");
        when(repo.existsByKindAndServiceTypeAndNameIgnoreCase(AgencySettingKind.SERVICE_PREFERENCE, ServiceType.HOTEL, "Sea view"))
                .thenReturn(true);
        assertThatThrownBy(() -> service.create(create(AgencySettingKind.SERVICE_PREFERENCE, ServiceType.HOTEL, "Sea view")))
                .isInstanceOf(IllegalStateException.class).hasMessage("\"Sea view\" already exists");
    }

    @Test
    void listReadsSuppliersFromVendorsAndOtherKindsWithOrWithoutAType() {
        AgencySettingResponse v = AgencySettingResponse.builder().id("V1").build();
        when(vendorService.listAsLegacySettings(ServiceType.HOTEL)).thenReturn(List.of(v));
        when(repo.findByKindAndServiceTypeOrderBySortOrderAsc(AgencySettingKind.SERVICE_PREFERENCE, ServiceType.VISA))
                .thenReturn(List.of(row("p", AgencySettingKind.SERVICE_PREFERENCE, ServiceType.VISA, "Express", 0, false)));
        when(repo.findByKindOrderBySortOrderAsc(AgencySettingKind.DOCUMENT_TYPE))
                .thenReturn(List.of(row("d", AgencySettingKind.DOCUMENT_TYPE, null, "Passport", 0, true)));

        assertThat(service.list(AgencySettingKind.SUPPLIER, ServiceType.HOTEL)).containsExactly(v);
        assertThat(service.list(AgencySettingKind.SERVICE_PREFERENCE, ServiceType.VISA))
                .extracting(AgencySettingResponse::getName).containsExactly("Express");
        assertThat(service.list(AgencySettingKind.DOCUMENT_TYPE, null))
                .extracting(AgencySettingResponse::getIsDefault).containsExactly(true);
    }

    @Test
    void updateOfAnUnknownSettingFails() {
        when(repo.findById("S9")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update("S9", new AgencySettingUpdateRequest()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Setting not found: S9");
        assertThatThrownBy(() -> service.delete("S9"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Setting not found: S9");
    }

    @Test
    void hidingAndReorderingADefaultKeepsItsNameAndAPreferenceRenameChecksWithinItsType() {
        AgencySetting def = row("S1", AgencySettingKind.TRAVEL_CATEGORY, null, "Hotel", 0, true);
        when(repo.findById("S1")).thenReturn(Optional.of(def));
        AgencySettingUpdateRequest r = new AgencySettingUpdateRequest();
        r.setName("Hotel");
        r.setIsActive(false);
        r.setSortOrder(7);

        AgencySettingResponse out = service.update("S1", r);
        assertThat(out.getIsActive()).isFalse();
        assertThat(out.getSortOrder()).isEqualTo(7);
        assertThat(out.getName()).isEqualTo("Hotel");

        AgencySetting pref = row("S2", AgencySettingKind.SERVICE_PREFERENCE, ServiceType.FLIGHT, "Window", 0, false);
        pref.setIsDefault(null);
        when(repo.findById("S2")).thenReturn(Optional.of(pref));
        when(repo.existsByKindAndServiceTypeAndNameIgnoreCase(AgencySettingKind.SERVICE_PREFERENCE, ServiceType.FLIGHT, "Window seat"))
                .thenReturn(false);
        AgencySettingUpdateRequest rename = new AgencySettingUpdateRequest();
        rename.setName("Window seat");
        assertThat(service.update("S2", rename).getName()).isEqualTo("Window seat");
        assertThat(service.update("S2", new AgencySettingUpdateRequest()).getName()).isEqualTo("Window seat");
        ArgumentCaptor<AgencySetting> saved = ArgumentCaptor.forClass(AgencySetting.class);
        verify(repo, org.mockito.Mockito.times(3)).save(saved.capture());
        assertThat(saved.getAllValues().get(1).getIsActive()).isTrue();
    }

    @Test
    void deletingACustomOptionRemovesIt() {
        AgencySetting custom = row("S1", AgencySettingKind.LEAD_SOURCE, null, "Instagram", 3, false);
        when(repo.findById("S1")).thenReturn(Optional.of(custom));

        service.delete("S1");

        verify(repo).delete(custom);
    }
}
