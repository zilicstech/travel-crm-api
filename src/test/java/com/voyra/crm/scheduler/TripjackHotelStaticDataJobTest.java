package com.voyra.crm.scheduler;

import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.models.ResolvedCredential;
import com.voyra.crm.service.booking.SupplierRegistry;
import com.voyra.crm.service.tripjack.TripjackHotelSearchProvider;
import com.voyra.crm.support.LogCapture;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TripjackHotelStaticDataJobTest {

    private final SupplierRegistry registry = mock(SupplierRegistry.class);
    private final TripjackHotelSearchProvider provider = mock(TripjackHotelSearchProvider.class);
    private final TripjackHotelStaticDataJob job = new TripjackHotelStaticDataJob(registry, provider);

    private static ResolvedCredential row(String id, SupplierProvider p) {
        return new ResolvedCredential(id, "Supplier " + id, p, p == SupplierProvider.MOCK ? SupplierEnvironment.MOCK : SupplierEnvironment.UAT,
                "x", "k", null, null);
    }

    @Test
    void refreshesEachTripjackHotelSupplierAndSkipsTheMock() {
        ResolvedCredential a = row("s1", SupplierProvider.TRIPJACK);
        ResolvedCredential mock = row("s2", SupplierProvider.MOCK);
        ResolvedCredential b = row("s3", SupplierProvider.TRIPJACK);
        when(registry.searchable(PricingProduct.HOTEL)).thenReturn(List.of(a, mock, b));

        job.run();

        verify(provider).refreshStaticData(a);
        verify(provider).refreshStaticData(b);
        verify(provider, never()).refreshStaticData(mock);
    }

    @Test
    void oneSupplierFailingIsNotedAndTheNextStillRuns() {
        ResolvedCredential a = row("s1", SupplierProvider.TRIPJACK);
        ResolvedCredential b = row("s3", SupplierProvider.TRIPJACK);
        ResolvedCredential c = row("s4", SupplierProvider.TRIPJACK);
        when(registry.searchable(PricingProduct.HOTEL)).thenReturn(List.of(a, b, c));
        doThrow(new IllegalStateException("still loading")).when(provider).refreshStaticData(a);
        doThrow(new RuntimeException("timeout")).when(provider).refreshStaticData(b);

        try (LogCapture log = new LogCapture(TripjackHotelStaticDataJob.class)) {
            job.run();
            assertThat(log.lines()).containsExactly("INFO Tripjack hotel static data not loaded: still loading",
                    "WARN Tripjack hotel static data refresh failed: timeout");
        }
        verify(provider).refreshStaticData(c);
    }

    @Test
    void noHotelSupplierMeansNoCall() {
        when(registry.searchable(PricingProduct.HOTEL)).thenReturn(List.of());
        job.run();
        verify(provider, never()).refreshStaticData(any());
    }
}
