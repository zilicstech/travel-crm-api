package com.voyra.crm.service;

import com.voyra.crm.dto.FlightSearchQuery;
import com.voyra.crm.dto.SupplierTestResult;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.models.ResolvedCredential;
import com.voyra.crm.service.tripjack.TripjackFlightSearchProvider;
import org.junit.jupiter.api.BeforeEach;
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

/** The test call: a fixed DEL-BOM search with the tested row's own key, always audited with its id, never a key in the answer. */
@ExtendWith(MockitoExtension.class)
class PlatformSupplierTestServiceTest {

    private static final ResolvedCredential ROW = new ResolvedCredential(SupplierProvider.TRIPJACK, SupplierEnvironment.PROD,
            "https://api.tripjack.com", "tj_row_key_4444", null);

    @Mock private ObjectProvider<TripjackFlightSearchProvider> flightProvider;
    @Mock private TripjackFlightSearchProvider tripjack;
    @Mock private PlatformSupplierCredentialService credentialService;
    @Mock private PlatformAuditService platformAuditService;

    @BeforeEach
    void setUp() {
        when(credentialService.resolve("C1")).thenReturn(ROW);
    }

    private PlatformSupplierTestService service() {
        return new PlatformSupplierTestService(flightProvider, credentialService, platformAuditService);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> auditDetail() {
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(platformAuditService).record(eq(PlatformAuditAction.CREDENTIAL_TEST), eq("SUPPLIER_CREDENTIAL"),
                eq("C1"), detail.capture());
        return detail.getValue();
    }

    @Test
    void searchesDelhiToMumbaiAMonthOutWithThatRowsKey() {
        when(flightProvider.getIfAvailable()).thenReturn(tripjack);
        when(tripjack.search(any(), eq(ROW))).thenReturn(List.of());

        SupplierTestResult result = service().testCall("C1");

        ArgumentCaptor<FlightSearchQuery> query = ArgumentCaptor.forClass(FlightSearchQuery.class);
        verify(tripjack).search(query.capture(), eq(ROW));
        assertThat(query.getValue().getOrigin()).isEqualTo("DEL");
        assertThat(query.getValue().getDestination()).isEqualTo("BOM");
        assertThat(query.getValue().getDepartDate()).isEqualTo(LocalDate.now().plusDays(30));
        assertThat(query.getValue().getAdults()).isEqualTo(1);
        assertThat(result.isOk()).isTrue();
        assertThat(auditDetail()).containsEntry("credentialId", "C1").containsEntry("provider", "TRIPJACK")
                .containsEntry("ok", true).containsKey("millis");
    }

    @Test
    void aFailingCallIsNotOkAndCarriesTheMessage() {
        when(flightProvider.getIfAvailable()).thenReturn(tripjack);
        when(tripjack.search(any(), eq(ROW))).thenThrow(new IllegalStateException("Tripjack flight search failed: HTTP 401"));

        SupplierTestResult result = service().testCall("C1");

        assertThat(result.isOk()).isFalse();
        assertThat(result.getMessage()).isEqualTo("Tripjack flight search failed: HTTP 401");
        assertThat(auditDetail()).containsEntry("ok", false);
    }

    @Test
    void aFailureWithNoMessageIsNamedByItsType() {
        when(flightProvider.getIfAvailable()).thenReturn(tripjack);
        when(tripjack.search(any(), eq(ROW))).thenThrow(new NullPointerException());

        SupplierTestResult result = service().testCall("C1");

        assertThat(result.isOk()).isFalse();
        assertThat(result.getMessage()).isEqualTo("NullPointerException");
    }

    @Test
    void mockSupplierReportsDisabledWithoutCallingAnything() {
        when(flightProvider.getIfAvailable()).thenReturn(null);

        SupplierTestResult result = service().testCall("C1");

        assertThat(result.isOk()).isFalse();
        assertThat(result.getMessage()).isEqualTo("Tripjack provider is disabled (FLIGHT_SUPPLIER=mock)");
        assertThat(auditDetail()).containsEntry("ok", false);
    }
}
