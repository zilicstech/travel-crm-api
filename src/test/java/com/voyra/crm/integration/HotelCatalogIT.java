package com.voyra.crm.integration;

import com.voyra.crm.AbstractIntegrationTest;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.exception.BookingConflictException;
import com.voyra.crm.models.CatalogHotel;
import com.voyra.crm.models.CatalogSummary;
import com.voyra.crm.models.ResolvedCredential;
import com.voyra.crm.service.HotelCatalogService;
import com.voyra.crm.service.HotelSearchProvider;
import com.voyra.crm.service.booking.SupplierRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The catalogue's SQL against real Postgres: public V3 on a fresh database (pg_trgm included), the claim and takeover,
 * the upsert, pruning only after a load that looks complete, and the name lookup's ranking, escaping and speed.
 */
class HotelCatalogIT extends AbstractIntegrationTest {

    @Autowired
    private HotelCatalogService service;
    @Autowired
    private JdbcTemplate jdbc;
    @MockBean
    private SupplierRegistry registry;
    @MockBean
    private com.voyra.crm.service.PlatformAuditService audit;

    private ResolvedCredential supplier;
    private final HotelSearchProvider provider = mock(HotelSearchProvider.class);

    @BeforeEach
    void supplier() {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO public.platform_supplier_credential (id, provider, environment, base_url, api_key_encrypted, name) "
                + "VALUES (?, 'MOCK', 'MOCK', 'mock://local', '', ?)", id, "Catalogue " + id.substring(0, 8));
        supplier = new ResolvedCredential(id, "Catalogue supplier", SupplierProvider.MOCK, SupplierEnvironment.MOCK, "mock://local", "", null, Instant.EPOCH);
        when(registry.find(id)).thenReturn(Optional.of(supplier));
        when(registry.hotelSearch(SupplierProvider.MOCK)).thenReturn(provider);
        when(registry.searchable(PricingProduct.HOTEL)).thenReturn(List.of(supplier));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "runner", (java.util.concurrent.Executor) Runnable::run);
    }

    private void supplierLists(CatalogSummary summary, List<CatalogHotel> hotels) {
        org.mockito.Mockito.doAnswer(i -> {
            i.<Consumer<List<CatalogHotel>>>getArgument(1).accept(hotels);
            return summary;
        }).when(provider).catalog(any(), any());
    }

    private static CatalogHotel hotel(String id, String name) {
        return new CatalogHotel(id, name, "Goa", "IN", "India", 4, "Hotel");
    }

    private List<String> names() {
        return jdbc.queryForList("SELECT name FROM public.hotel_catalog WHERE supplier_id = ? ORDER BY hotel_id", String.class, supplier.id());
    }

    private String status() {
        return jdbc.queryForObject("SELECT status FROM public.hotel_catalog_sync WHERE supplier_id = ?", String.class, supplier.id());
    }

    @Test
    void aLoadCreatesTheCatalogueARefreshUpdatesAndPrunesAndABrokenOneKeepsIt() {
        supplierLists(new CatalogSummary(10, 10, 0, 1, null),
                java.util.stream.IntStream.range(0, 10).mapToObj(i -> hotel("1" + i, "Hotel " + i)).toList());
        service.refresh(supplier.id());
        assertThat(names()).hasSize(10);
        assertThat(status()).isEqualTo("DONE");

        // nine come back, one renamed: the tenth is no longer listed
        supplierLists(new CatalogSummary(9, 9, 0, 1, null),
                java.util.stream.IntStream.range(0, 9).mapToObj(i -> hotel("1" + i, i == 0 ? "Renamed" : "Hotel " + i)).toList());
        service.refresh(supplier.id());
        assertThat(names()).hasSize(9).first().isEqualTo("Renamed");

        // a load that lost a page writes what it saw but deletes nothing
        supplierLists(new CatalogSummary(9, 1, 0, 1, "lost a page"), List.of(hotel("99", "New")));
        service.refresh(supplier.id());
        assertThat(names()).hasSize(10);
        assertThat(status()).isEqualTo("FAILED");
        assertThat(service.status()).filteredOn(s -> s.supplierId().equals(supplier.id())).singleElement()
                .satisfies(s -> {
                    assertThat(s.hotels()).isEqualTo(10);
                    assertThat(s.lastError()).isEqualTo("lost a page");
                    assertThat(s.lastLoadedAt()).isNotNull();
                });
    }

    @Test
    void aRunningLoadRefusesASecondUntilItsHeartbeatGoesStale() {
        supplierLists(new CatalogSummary(1, 1, 0, 1, null), List.of(hotel("1", "A")));
        jdbc.update("INSERT INTO public.hotel_catalog_sync (supplier_id, status, run_id, heartbeat_at) VALUES (?, 'RUNNING', ?, now())",
                supplier.id(), UUID.randomUUID());
        assertThatThrownBy(() -> service.refresh(supplier.id())).isInstanceOf(BookingConflictException.class);

        jdbc.update("UPDATE public.hotel_catalog_sync SET heartbeat_at = now() - interval '20 minutes' WHERE supplier_id = ?", supplier.id());
        service.refresh(supplier.id());
        assertThat(status()).isEqualTo("DONE");
    }

    @Test
    void namesStartingWithTheTextComeFirstWildcardsAreLiteralAndTheLookupStaysFast() {
        jdbc.update("INSERT INTO public.hotel_catalog (supplier_id, hotel_id, name, city, star_rating, seen_at) "
                + "SELECT ?, 'g' || g, 'Generated Hotel ' || g, 'City ' || (g % 500), 1 + g % 5, now() FROM generate_series(1, 100000) g", supplier.id());
        jdbc.update("INSERT INTO public.hotel_catalog (supplier_id, hotel_id, name, city, star_rating, seen_at) VALUES "
                + "(?, 't1', 'The Taj Mahal Palace', 'Mumbai', 5, now()), (?, 't2', 'Taj Exotica', 'Goa', 5, now()), "
                + "(?, 't3', 'Vivanta by Taj', 'Goa', NULL, now()), (?, 't4', '100% Pure Stay', 'Goa', 3, now())",
                supplier.id(), supplier.id(), supplier.id(), supplier.id());
        jdbc.execute("ANALYZE public.hotel_catalog");

        long started = System.nanoTime();
        List<String> taj = service.suggest("taj").stream().map(s -> s.name()).toList();
        long ms = (System.nanoTime() - started) / 1_000_000;

        assertThat(taj).first().isEqualTo("Taj Exotica");
        assertThat(taj).contains("The Taj Mahal Palace", "Vivanta by Taj").doesNotContain("100% Pure Stay");
        assertThat(service.suggest("0% P")).extracting(s -> s.name()).containsExactly("100% Pure Stay");
        assertThat(service.suggest("___")).isEmpty();
        assertThat(service.suggest("Generated Hotel 9999")).first().satisfies(s -> assertThat(s.name()).isEqualTo("Generated Hotel 9999"));
        assertThat(ms).isLessThan(500);
    }
}
