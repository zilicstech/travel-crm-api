package com.voyra.crm.service;

import com.voyra.crm.dto.HotelCatalogStatusResponse;
import com.voyra.crm.dto.HotelSuggestionResponse;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.exception.BookingConflictException;
import com.voyra.crm.exception.BookingRequestException;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.models.CatalogHotel;
import com.voyra.crm.models.CatalogSummary;
import com.voyra.crm.models.ResolvedCredential;
import com.voyra.crm.service.booking.SupplierRegistry;
import com.voyra.crm.support.LogCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HotelCatalogServiceTest {

    /** Records every statement and answers updates by the start of their SQL; everything runs inline. */
    static class FakeJdbc extends JdbcTemplate {
        final List<String> sql = new ArrayList<>();
        final List<Object[]> args = new ArrayList<>();
        final Map<String, Integer> updates = new HashMap<>();
        RuntimeException batchError;
        RuntimeException rowError;
        RuntimeException queryError;
        int previous;
        List<Map<String, Object>> rows = List.of();

        int answer(String statement) {
            return updates.entrySet().stream().filter(e -> statement.startsWith(e.getKey())).map(Map.Entry::getValue).findFirst().orElse(1);
        }

        @Override
        public int update(String statement, Object... a) {
            sql.add(statement);
            args.add(a);
            if (rowError != null && statement.startsWith("INSERT INTO public.hotel_catalog (") && "bad".equals(a[1])) throw rowError;
            return answer(statement);
        }

        @Override
        public int[] batchUpdate(String statement, List<Object[]> batch) {
            sql.add("BATCH " + batch.size());
            args.add(new Object[0]);
            if (batchError != null) throw batchError;
            return new int[batch.size()];
        }

        @Override
        public <T> T queryForObject(String statement, Class<T> type, Object... a) {
            return type.cast(previous);
        }

        @Override
        public void execute(String statement) {
            sql.add(statement);
            args.add(new Object[0]);
        }

        @Override
        public <T> List<T> query(String statement, RowMapper<T> mapper, Object... a) {
            sql.add(statement);
            args.add(a);
            if (queryError != null) throw queryError;
            return map(mapper);
        }

        @Override
        public <T> List<T> query(String statement, RowMapper<T> mapper) {
            return map(mapper);
        }

        private <T> List<T> map(RowMapper<T> mapper) {
            List<T> out = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                try {
                    ResultSet rs = mock(ResultSet.class);
                    for (var e : row.entrySet()) {
                        when(rs.getString(e.getKey())).thenReturn(e.getValue() instanceof String s ? s : null);
                        when(rs.getLong(e.getKey())).thenReturn(e.getValue() instanceof Long l ? l : 0L);
                        when(rs.getTimestamp(e.getKey())).thenReturn(e.getValue() instanceof Timestamp t ? t : null);
                        when(rs.getObject(e.getKey(), Integer.class)).thenReturn(e.getValue() instanceof Integer i ? i : null);
                    }
                    out.add(mapper.mapRow(rs, out.size()));
                } catch (SQLException ex) {
                    throw new IllegalStateException(ex);
                }
            }
            return out;
        }
    }

    private static final ResolvedCredential TRIPJACK = new ResolvedCredential("s1", "Tripjack", SupplierProvider.TRIPJACK,
            SupplierEnvironment.PROD, "https://api.tripjack.com", "k", null, Instant.EPOCH);
    private static final CatalogHotel HOTEL = new CatalogHotel("100", "Taj Exotica", "Goa", "IN", "India", 5, "Resort");

    private final SupplierRegistry registry = mock(SupplierRegistry.class);
    private final PlatformAuditService audit = mock(PlatformAuditService.class);
    private final FakeJdbc jdbc = new FakeJdbc();
    private HotelCatalogService service;

    @BeforeEach
    void setUp() {
        service = new HotelCatalogService(registry, jdbc, mock(PlatformTransactionManager.class), audit);
        service.runner = Runnable::run;
        when(registry.find("s1")).thenReturn(Optional.of(TRIPJACK));
        jdbc.rows = List.of(Map.of("id", "s1", "name", "Tripjack", "status", "RUNNING", "stored", 0L,
                "started_at", Timestamp.from(Instant.EPOCH)));
    }

    private HotelSearchProvider provider(CatalogSummary summary, List<List<CatalogHotel>> batches) {
        HotelSearchProvider p = mock(HotelSearchProvider.class);
        when(p.catalog(eq(TRIPJACK), any())).thenAnswer(i -> {
            Consumer<List<CatalogHotel>> sink = i.getArgument(1);
            batches.forEach(sink);
            return summary;
        });
        when(registry.hotelSearch(SupplierProvider.TRIPJACK)).thenReturn(p);
        return p;
    }

    /** The reason of each FAILED write, in order (update args: finished_at, reason, supplier, run). */
    private List<String> failReasons() {
        List<String> reasons = new ArrayList<>();
        for (int i = 0; i < jdbc.sql.size(); i++) {
            if (jdbc.sql.get(i).contains("'FAILED', finished_at")) reasons.add((String) jdbc.args.get(i)[1]);
        }
        return reasons;
    }

    private boolean ran(String prefix) {
        return jdbc.sql.stream().anyMatch(s -> s.startsWith(prefix));
    }

    @Test
    void aLoadClaimsTheSupplierUpsertsEveryBatchThenMarksItDoneAndPrunesWhatIsNoLongerListed() {
        provider(new CatalogSummary(2, 2, 0, 1, null), List.of(List.of(HOTEL), List.of(new CatalogHotel("101", "Taj Fort", "Goa", null, null, null, null))));
        jdbc.updates.put("DELETE", 3);
        try (LogCapture log = new LogCapture(HotelCatalogService.class)) {
            HotelCatalogStatusResponse status = service.refresh("s1");

            assertThat(status.supplierName()).isEqualTo("Tripjack");
            assertThat(status.startedAt()).isEqualTo(Instant.EPOCH);
            assertThat(status.finishedAt()).isNull();
            assertThat(log.lines()).anyMatch(l -> l.contains("2 hotels") && l.contains("3 no longer listed removed"));
        }
        assertThat(ran("INSERT INTO public.hotel_catalog_sync")).isTrue();
        assertThat(jdbc.sql).contains("BATCH 1");
        assertThat(ran("UPDATE public.hotel_catalog_sync SET status = 'DONE'")).isTrue();
        assertThat(ran("DELETE FROM public.hotel_catalog WHERE")).isTrue();
        verify(audit).record(eq(PlatformAuditAction.HOTEL_CATALOG_REFRESH), eq("SUPPLIER"), eq("s1"), any());
    }

    @Test
    void aLoadTakenOverBeforeItEndsNeitherMarksNorPrunes() {
        provider(new CatalogSummary(1, 1, 0, 1, null), List.of(List.of(HOTEL)));
        jdbc.updates.put("UPDATE public.hotel_catalog_sync SET status = 'DONE'", 0);
        try (LogCapture log = new LogCapture(HotelCatalogService.class)) {
            service.refresh("s1");
            assertThat(log.lines()).anyMatch(l -> l.contains("taken over by another load"));
        }
        assertThat(ran("DELETE")).isFalse();
    }

    @Test
    void aFailedBatchIsStoredRowByRowAndTooManyBadRowsFailTheLoad() {
        jdbc.batchError = new DataIntegrityViolationException("too long");
        jdbc.rowError = new DataIntegrityViolationException("bad row");
        provider(new CatalogSummary(2, 2, 0, 1, null), List.of(List.of(HOTEL, new CatalogHotel("bad", "X", "Goa", null, null, null, null))));

        service.refresh("s1");

        assertThat(ran("UPDATE public.hotel_catalog_sync SET status = 'FAILED'")).isTrue();
        assertThat(failReasons()).containsExactly("1 hotels could not be stored");
        assertThat(ran("DELETE")).isFalse();
    }

    @Test
    void aLoadThatLostItsClaimStopsAtTheNextHeartbeat() {
        provider(new CatalogSummary(1, 1, 0, 1, null), List.of(List.of(HOTEL)));
        jdbc.updates.put("UPDATE public.hotel_catalog_sync SET heartbeat_at", 0);

        service.refresh("s1");

        assertThat(ran("UPDATE public.hotel_catalog_sync SET status = 'DONE'")).isFalse();
        assertThat(ran("UPDATE public.hotel_catalog_sync SET status = 'FAILED'")).isTrue();
    }

    @Test
    void aProviderErrorFailsTheLoadWithAShortReason() {
        HotelSearchProvider p = provider(CatalogSummary.EMPTY, List.of());
        when(p.catalog(eq(TRIPJACK), any())).thenThrow(new IllegalStateException("x".repeat(400))).thenThrow(new NullPointerException());

        service.refresh("s1");
        service.refresh("s1");

        assertThat(failReasons()).containsExactly("x".repeat(300), "NullPointerException");
    }

    @Test
    void anUnknownSupplierAOneWithoutHotelSearchAndARunningLoadAreRefused() {
        when(registry.find("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.refresh("nope")).isInstanceOf(NotFoundException.class);

        when(registry.hotelSearch(SupplierProvider.TRIPJACK)).thenThrow(new IllegalStateException("none"));
        assertThatThrownBy(() -> service.refresh("s1")).isInstanceOf(BookingRequestException.class)
                .extracting("code").isEqualTo("NO_HOTEL_SEARCH");
    }

    @Test
    void aSecondLoadWhileOneRunsIs409() {
        provider(CatalogSummary.EMPTY, List.of());
        jdbc.updates.put("UPDATE public.hotel_catalog_sync SET status = 'RUNNING'", 0);
        assertThatThrownBy(() -> service.refresh("s1")).isInstanceOf(BookingConflictException.class)
                .extracting("code").isEqualTo("CATALOG_RUNNING");
        verify(audit, never()).record(any(), any(), any(), any());
    }

    @Test
    void onlyALoadThatLooksCompleteMayPrune() {
        assertThat(HotelCatalogService.problem(new CatalogSummary(10, 10, 0, 1, "lost page"), 10, 0, 0)).isEqualTo("lost page");
        assertThat(HotelCatalogService.problem(new CatalogSummary(10, 0, 0, 1, null), 0, 0, 0)).isEqualTo("The supplier listed no hotels");
        assertThat(HotelCatalogService.problem(new CatalogSummary(10, 10, 2, 10, null), 10, 0, 0)).isEqualTo("2 of 10 hotel content calls failed");
        assertThat(HotelCatalogService.problem(new CatalogSummary(10, 10, 1, 10, null), 10, 0, 0)).isNull();
        assertThat(HotelCatalogService.problem(new CatalogSummary(10, 10, 0, 0, null), 89, 0, 100)).isEqualTo("Only 89 hotels came back, against 100 last time");
        assertThat(HotelCatalogService.problem(new CatalogSummary(10, 10, 0, 0, null), 90, 0, 100)).isNull();
    }

    @Test
    void suggestionsComeFromSuppliersWithHotelSearchOnClosestFirstAndEscaped() {
        when(registry.searchable(PricingProduct.HOTEL)).thenReturn(List.of(TRIPJACK));
        jdbc.rows = List.of(
                Map.of("supplier_id", "s1", "hotel_id", "100", "name", "Taj Exotica", "city", "Goa", "country_code", "IN", "country_name", "India", "star_rating", 5),
                Map.of("supplier_id", "s1", "hotel_id", "101", "name", "Taj Inn", "city", "Goa"));

        List<HotelSuggestionResponse> out = service.suggest("  t%j_\\x  ");

        assertThat(out).extracting(HotelSuggestionResponse::hotelId).containsExactly("s1:100", "s1:101");
        assertThat(out.get(0).starRating()).isEqualTo(5);
        assertThat(out.get(1).starRating()).isNull();
        assertThat(out.get(0).supplierName()).isEqualTo("Tripjack");
        assertThat(jdbc.sql).contains("SET LOCAL statement_timeout = '500ms'");
        assertThat(jdbc.args.get(jdbc.args.size() - 1)).containsExactly("s1", "%t\\%j\\_\\\\x%", "t\\%j\\_\\\\x%", "t%j_\\x");
    }

    @Test
    void shortBlankOrUnservedQueriesAskNothingAndALongOneIsCut() {
        assertThat(service.suggest(null)).isEmpty();
        assertThat(service.suggest("  ab  ")).isEmpty();
        when(registry.searchable(PricingProduct.HOTEL)).thenReturn(List.of());
        assertThat(service.suggest("taj")).isEmpty();
        assertThat(jdbc.sql).isEmpty();

        when(registry.searchable(PricingProduct.HOTEL)).thenReturn(List.of(TRIPJACK, TRIPJACK));
        jdbc.rows = List.of();
        service.suggest("a".repeat(80));
        assertThat(jdbc.args.get(jdbc.args.size() - 1)[3]).isEqualTo("a".repeat(60));
    }

    @Test
    void aSlowOrFailedLookupOffersNoHotels() {
        when(registry.searchable(PricingProduct.HOTEL)).thenReturn(List.of(TRIPJACK));
        jdbc.queryError = new QueryTimeoutException("statement timeout");
        assertThat(service.suggest("taj")).isEmpty();
    }

    @Test
    void byDefaultALoadRunsOnItsOwnThreadAndTheRequestReturnsAtOnce() throws Exception {
        HotelCatalogService real = new HotelCatalogService(registry, jdbc, mock(PlatformTransactionManager.class), audit);
        java.util.concurrent.CountDownLatch loaded = new java.util.concurrent.CountDownLatch(1);
        String[] thread = new String[1];
        HotelSearchProvider p = mock(HotelSearchProvider.class);
        when(p.catalog(eq(TRIPJACK), any())).thenAnswer(i -> {
            thread[0] = Thread.currentThread().getName();
            loaded.countDown();
            return CatalogSummary.EMPTY;
        });
        when(registry.hotelSearch(SupplierProvider.TRIPJACK)).thenReturn(p);

        real.refresh("s1");

        assertThat(loaded.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(thread[0]).startsWith("hotel-catalog-");
    }
}
