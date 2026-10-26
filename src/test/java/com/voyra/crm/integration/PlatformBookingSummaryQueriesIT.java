package com.voyra.crm.integration;

import com.voyra.crm.AbstractIntegrationTest;
import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.enums.PlatformBookingStatus;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.repository.PlatformBookingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The agency summary's three queries against real Postgres (the JPQL aggregate, the native jsonb travel-date query and the
 * attention list). Needs Docker (Testcontainers), like the other *IT classes, so it is outside {@code mvn verify}.
 */
class PlatformBookingSummaryQueriesIT extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.now();

    @Autowired
    private PlatformBookingRepository repository;

    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    private void row(String id, String tenant, String bookedBy, PricingProduct product, PlatformBookingStatus status,
                     String displayed, String commission, String tds, String travelDate, Instant createdAt) {
        Map<String, Object> snapshot = new HashMap<>();
        if (travelDate != null && product == PricingProduct.FLIGHT) {
            snapshot.put("travelDate", travelDate);
        }
        repository.save(PlatformBooking.builder().id(id).tenantId(tenant).bookedBy(bookedBy).product(product).status(status)
                .environment(SupplierEnvironment.MOCK).displayed(displayed == null ? null : new BigDecimal(displayed))
                .agencyCommission(commission == null ? null : new BigDecimal(commission)).tds(tds == null ? null : new BigDecimal(tds))
                .pricingSnapshot(snapshot)
                .itinerary(product == PricingProduct.HOTEL && travelDate != null ? Map.of("stay", Map.of("checkIn", travelDate)) : Map.of())
                .createdAt(createdAt).build());
    }

    @Test
    void summaryQueriesAggregateListUpcomingTripsAndAttention() {
        Instant inRange = NOW.minus(Duration.ofHours(1));
        row("f1", "T1", "A1", PricingProduct.FLIGHT, PlatformBookingStatus.TICKETED, "4500.00", "100.00", "5.00", "2099-01-10", inRange);
        row("f2", "T1", "T1", PricingProduct.FLIGHT, PlatformBookingStatus.TICKETED, "5500.00", null, null, "2099-01-05", inRange);
        row("h1", "T1", "A1", PricingProduct.HOTEL, PlatformBookingStatus.TICKETED, "9000.00", "200.00", "10.00", "2099-01-07", inRange);
        row("past", "T1", "A1", PricingProduct.FLIGHT, PlatformBookingStatus.TICKETED, "1.00", null, null, "2000-01-01", inRange);
        row("rev", "T1", "A1", PricingProduct.FLIGHT, PlatformBookingStatus.REVIEWING, "1.00", null, null, null, inRange);
        row("unk", "T1", "A1", PricingProduct.FLIGHT, PlatformBookingStatus.UNKNOWN, "1.00", null, null, null, inRange);
        row("old", "T1", "A1", PricingProduct.FLIGHT, PlatformBookingStatus.TICKETED, "7.00", null, null, null, NOW.minus(Duration.ofDays(40)));
        row("other", "T2", "B1", PricingProduct.FLIGHT, PlatformBookingStatus.TICKETED, "8.00", null, null, "2099-01-01", inRange);

        List<Object[]> rows = repository.summarize("T1", null, NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(1)),
                EnumSet.of(PlatformBookingStatus.REVIEWING, PlatformBookingStatus.ABORTED));
        Map<String, Object[]> byKey = new HashMap<>();
        rows.forEach(r -> byKey.put(r[0] + "/" + r[1], r));
        assertThat(byKey).containsOnlyKeys("FLIGHT/TICKETED", "HOTEL/TICKETED", "FLIGHT/UNKNOWN");
        assertThat(((Number) byKey.get("FLIGHT/TICKETED")[2]).intValue()).isEqualTo(3);
        assertThat(new BigDecimal(byKey.get("FLIGHT/TICKETED")[3].toString())).isEqualByComparingTo("10001.00");
        assertThat(new BigDecimal(byKey.get("FLIGHT/TICKETED")[4].toString())).isEqualByComparingTo("95.00");
        assertThat(new BigDecimal(byKey.get("HOTEL/TICKETED")[4].toString())).isEqualByComparingTo("190.00");

        assertThat(repository.summarize("T1", "A1", NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(1)),
                EnumSet.of(PlatformBookingStatus.REVIEWING))).hasSize(3); // FLIGHT/TICKETED, HOTEL/TICKETED, FLIGHT/UNKNOWN for A1 only

        assertThat(repository.findUpcoming("T1", null, "2026-10-27")).extracting(PlatformBooking::getId).containsExactly("f2", "h1", "f1");
        assertThat(repository.findUpcoming("T1", "A1", "2026-10-27")).extracting(PlatformBooking::getId).containsExactly("h1", "f1");

        assertThat(repository.findNewestInStatuses("T1", null, EnumSet.of(PlatformBookingStatus.BOOKING, PlatformBookingStatus.UNKNOWN,
                PlatformBookingStatus.CANCELLING), PageRequest.of(0, 5))).extracting(PlatformBooking::getId).containsExactly("unk");
    }
}
