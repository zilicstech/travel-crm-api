package com.voyra.crm.service;

import com.voyra.crm.dto.FlightSearchQuery;
import com.voyra.crm.dto.SupplierTestResult;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.service.tripjack.TripjackFlightSearchProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The test call: a fixed DEL-BOM search through the real provider, always audited, never a key in the answer. */
@ExtendWith(MockitoExtension.class)
class PlatformSupplierTestServiceTest {

    @Mock private ObjectProvider<TripjackFlightSearchProvider> flightProvider;
    @Mock private TripjackFlightSearchProvider tripjack;
    @Mock private PlatformAuditService platformAuditService;

    private PlatformSupplierTestService service() {
        return new PlatformSupplierTestService(flightProvider, platformAuditService);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> auditDetail() {
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(platformAuditService).record(eq(PlatformAuditAction.CREDENTIAL_TEST), eq("SUPPLIER_CREDENTIAL"),
                eq("TRIPJACK"), detail.capture());
        return detail.getValue();
    }

    @Test
    void searchesDelhiToMumbaiAMonthOutForOneAdult() {
        when(flightProvider.getIfAvailable()).thenReturn(tripjack);
        when(tripjack.search(any())).thenReturn(List.of());

        SupplierTestResult result = service().testCall(SupplierProvider.TRIPJACK);

        ArgumentCaptor<FlightSearchQuery> query = ArgumentCaptor.forClass(FlightSearchQuery.class);
        verify(tripjack).search(query.capture());
        assertThat(query.getValue().getOrigin()).isEqualTo("DEL");
        assertThat(query.getValue().getDestination()).isEqualTo("BOM");
        assertThat(query.getValue().getDepartDate()).isEqualTo(LocalDate.now().plusDays(30));
        assertThat(query.getValue().getAdults()).isEqualTo(1);
        assertThat(result.isOk()).isTrue();
        assertThat(auditDetail()).containsEntry("provider", "TRIPJACK").containsEntry("ok", true).containsKey("millis");
    }

    @Test
    void aFailingCallIsNotOkAndCarriesTheMessage() {
        when(flightProvider.getIfAvailable()).thenReturn(tripjack);
        when(tripjack.search(any())).thenThrow(new IllegalStateException("Tripjack is not configured on the platform"));

        SupplierTestResult result = service().testCall(SupplierProvider.TRIPJACK);

        assertThat(result.isOk()).isFalse();
        assertThat(result.getMessage()).isEqualTo("Tripjack is not configured on the platform");
        assertThat(auditDetail()).containsEntry("ok", false);
    }

    @Test
    void mockSupplierReportsDisabledWithoutCallingAnything() {
        when(flightProvider.getIfAvailable()).thenReturn(null);

        SupplierTestResult result = service().testCall(SupplierProvider.TRIPJACK);

        assertThat(result.isOk()).isFalse();
        assertThat(result.getMessage()).isEqualTo("Tripjack provider is disabled (FLIGHT_SUPPLIER=mock)");
        assertThat(auditDetail()).containsEntry("ok", false);
    }
}
