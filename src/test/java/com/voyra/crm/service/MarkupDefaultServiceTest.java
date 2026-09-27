package com.voyra.crm.service;

import com.voyra.crm.dto.MarkupDefaultResponse;
import com.voyra.crm.dto.MarkupDefaultUpdateRequest;
import com.voyra.crm.entity.MarkupDefault;
import com.voyra.crm.enums.MarkupMode;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.MarkupDefaultRepository;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarkupDefaultServiceTest {

    @Mock
    private MarkupDefaultRepository markupDefaultRepository;
    @Mock
    private AuditService auditService;

    @InjectMocks
    private MarkupDefaultService markupDefaultService;

    @BeforeEach
    void authenticateAsOwner() {
        CustomUserPrincipal principal = new CustomUserPrincipal("O1", "owner", UserType.AGENCY_OWNER, "T1");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listReturnsEveryServiceTypeEvenWithNoRowsYet() {
        when(markupDefaultRepository.findAll()).thenReturn(List.of());

        List<MarkupDefaultResponse> rows = markupDefaultService.list();

        assertThat(rows).extracting(MarkupDefaultResponse::getServiceType)
                .containsExactlyInAnyOrder(ServiceType.FLIGHT, ServiceType.HOTEL, ServiceType.VISA, ServiceType.TRANSFER);
        assertThat(rows).allSatisfy(r -> assertThat(r.getValue()).isEqualByComparingTo("0"));
    }

    @Test
    void listReflectsAnExistingRowForOneType() {
        when(markupDefaultRepository.findAll()).thenReturn(List.of(
                MarkupDefault.builder().serviceType(ServiceType.FLIGHT).mode(MarkupMode.PERCENT)
                        .value(new BigDecimal("5.00")).build()));

        List<MarkupDefaultResponse> rows = markupDefaultService.list();

        MarkupDefaultResponse flight = rows.stream().filter(r -> r.getServiceType() == ServiceType.FLIGHT).findFirst().orElseThrow();
        assertThat(flight.getValue()).isEqualByComparingTo("5.00");
        MarkupDefaultResponse hotel = rows.stream().filter(r -> r.getServiceType() == ServiceType.HOTEL).findFirst().orElseThrow();
        assertThat(hotel.getValue()).isEqualByComparingTo("0");
    }

    @Test
    void upsertCreatesARowWhenNoneExistsYet() {
        when(markupDefaultRepository.findById(ServiceType.HOTEL)).thenReturn(Optional.empty());
        MarkupDefaultUpdateRequest request = new MarkupDefaultUpdateRequest();
        request.setMode(MarkupMode.FLAT);
        request.setValue(new BigDecimal("1500.00"));

        MarkupDefaultResponse response = markupDefaultService.upsert(ServiceType.HOTEL, request);

        assertThat(response.getMode()).isEqualTo(MarkupMode.FLAT);
        assertThat(response.getValue()).isEqualByComparingTo("1500.00");
        verify(auditService).recordUpdate(any(), any(), any(), any());
    }

    @Test
    void upsertOverwritesAnExistingRowInPlace() {
        MarkupDefault existing = MarkupDefault.builder().serviceType(ServiceType.FLIGHT)
                .mode(MarkupMode.PERCENT).value(new BigDecimal("5.00")).build();
        when(markupDefaultRepository.findById(ServiceType.FLIGHT)).thenReturn(Optional.of(existing));
        MarkupDefaultUpdateRequest request = new MarkupDefaultUpdateRequest();
        request.setMode(MarkupMode.PERCENT);
        request.setValue(new BigDecimal("7.50"));

        MarkupDefaultResponse response = markupDefaultService.upsert(ServiceType.FLIGHT, request);

        assertThat(response.getValue()).isEqualByComparingTo("7.50");
        verify(markupDefaultRepository).save(existing);
    }
}
