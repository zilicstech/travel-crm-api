package com.voyra.crm.service;

import com.voyra.crm.dto.HotelCatalogStatusResponse;
import com.voyra.crm.dto.HotelSuggestionResponse;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.exception.BookingConflictException;
import com.voyra.crm.exception.BookingRequestException;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.models.CatalogHotel;
import com.voyra.crm.models.CatalogSummary;
import com.voyra.crm.models.ResolvedCredential;
import com.voyra.crm.service.booking.SupplierRegistry;
import com.voyra.crm.util.SupplierIds;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Each hotel supplier's catalogue (id, name, city, stars, type) in {@code public.hotel_catalog}, so agents can search a
 * hotel by name: Tripjack's listing takes hotel ids only. A load runs only when a platform admin asks for one
 * ({@link #refresh}); it creates the catalogue or refreshes it the same way, upserting every hotel the supplier lists.
 *
 * <p>One load per supplier at a time, across instances: a load claims the supplier's {@code hotel_catalog_sync} row
 * with its own run id and keeps a heartbeat; a load whose heartbeat stops for 15 minutes can be taken over, and from
 * then on every write of the old one matches no row. Hotels the supplier no longer lists are deleted only after a load
 * that looks complete - nothing fatal, at most a tenth of its calls or rows lost, and not a big drop from the last load
 * - so a half-broken load never empties the catalogue. Public schema only; no tenant and no personal data.
 */
@Service
@Slf4j
public class HotelCatalogService {

    static final Duration TAKEOVER_AFTER = Duration.ofMinutes(15);
    static final int SUGGESTIONS = 8;

    private static final String UPSERT = "INSERT INTO public.hotel_catalog (supplier_id, hotel_id, name, city, country_code, "
            + "country_name, star_rating, property_type, seen_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (supplier_id, hotel_id) DO UPDATE SET name = EXCLUDED.name, city = EXCLUDED.city, "
            + "country_code = EXCLUDED.country_code, country_name = EXCLUDED.country_name, star_rating = EXCLUDED.star_rating, "
            + "property_type = EXCLUDED.property_type, seen_at = EXCLUDED.seen_at";

    private final SupplierRegistry registry;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PlatformAuditService audit;
    /** Loads run here, never on a request or scheduler thread; one per supplier at a time (the claim), up to 4 suppliers at once. Tests run them inline. */
    private static final java.util.concurrent.atomic.AtomicInteger THREADS = new java.util.concurrent.atomic.AtomicInteger();
    Executor runner = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "hotel-catalog-" + THREADS.incrementAndGet());
        t.setDaemon(true);
        return t;
    });
    Clock clock = Clock.systemUTC();

    public HotelCatalogService(SupplierRegistry registry, JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                               PlatformAuditService audit) {
        this.registry = registry;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.audit = audit;
    }

    /** Starts a load of the supplier's catalogue (202): creates it when there is none, refreshes it otherwise. */
    public HotelCatalogStatusResponse refresh(String supplierId) {
        ResolvedCredential supplier = registry.find(supplierId).orElseThrow(() -> new NotFoundException("Supplier not found"));
        HotelSearchProvider provider;
        try {
            provider = registry.hotelSearch(supplier.provider());
        } catch (IllegalStateException e) {
            throw new BookingRequestException("NO_HOTEL_SEARCH", "This supplier has no hotel search integration");
        }
        UUID runId = UUID.randomUUID();
        Instant start = clock.instant();
        jdbc.update("INSERT INTO public.hotel_catalog_sync (supplier_id, status) VALUES (?, 'FAILED') ON CONFLICT DO NOTHING", supplier.id());
        int claimed = jdbc.update("UPDATE public.hotel_catalog_sync SET status = 'RUNNING', run_id = ?, started_at = ?, heartbeat_at = ?, "
                        + "finished_at = NULL, last_error = NULL WHERE supplier_id = ? AND (status <> 'RUNNING' OR heartbeat_at < ?)",
                runId, ts(start), ts(start), supplier.id(), ts(start.minus(TAKEOVER_AFTER)));
        if (claimed == 0) {
            throw new BookingConflictException("CATALOG_RUNNING", "A catalogue load is already running for this supplier", Map.of());
        }
        audit.record(PlatformAuditAction.HOTEL_CATALOG_REFRESH, "SUPPLIER", supplier.id(), Map.of("supplierName", supplier.name()));
        runner.execute(() -> load(supplier, provider, runId, start));
        return status().stream().filter(s -> s.supplierId().equals(supplier.id())).findFirst().orElseThrow();
    }

    void load(ResolvedCredential supplier, HotelSearchProvider provider, UUID runId, Instant start) {
        long began = System.currentTimeMillis();
        int[] written = {0};
        int[] bad = {0};
        try {
            int previous = jdbc.queryForObject("SELECT hotels FROM public.hotel_catalog_sync WHERE supplier_id = ?", Integer.class, supplier.id());
            CatalogSummary summary = provider.catalog(supplier, batch -> write(supplier.id(), runId, start, batch, written, bad));
            String problem = problem(summary, written[0], bad[0], previous);
            if (problem == null) {
                // Marked DONE and pruned together, and only while this load still holds the claim.
                Integer removed = tx.execute(t -> jdbc.update("UPDATE public.hotel_catalog_sync SET status = 'DONE', finished_at = ?, "
                        + "last_done_at = ?, hotels = ? WHERE supplier_id = ? AND run_id = ?", ts(clock.instant()), ts(clock.instant()),
                        written[0], supplier.id(), runId) == 0 ? -1
                        : jdbc.update("DELETE FROM public.hotel_catalog WHERE supplier_id = ? AND seen_at < ?", supplier.id(), ts(start)));
                log.info("Hotel catalogue load for {} ended: {} hotels ({} expected, {} bad rows, {} of {} calls failed), {} in {} s",
                        supplier.name(), written[0], summary.expected(), bad[0], summary.failedCalls(), summary.totalCalls(),
                        removed < 0 ? "taken over by another load" : removed + " no longer listed removed", (System.currentTimeMillis() - began) / 1000);
            } else {
                fail(supplier, runId, problem);
            }
        } catch (RuntimeException e) {
            fail(supplier, runId, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** Why a finished load must not delete anything; null when it looks complete. */
    static String problem(CatalogSummary s, int written, int bad, int previous) {
        if (s.fatal() != null) return s.fatal();
        if (written == 0) return "The supplier listed no hotels";
        if (s.failedCalls() * 10L > s.totalCalls()) return s.failedCalls() + " of " + s.totalCalls() + " hotel content calls failed";
        if (bad * 10L > written + bad) return bad + " hotels could not be stored";
        if (written * 10L < previous * 9L) return "Only " + written + " hotels came back, against " + previous + " last time";
        return null;
    }

    /** One batch in its own transaction, retried row by row when the batch fails; stops the load if it was taken over. */
    private void write(String supplierId, UUID runId, Instant start, List<CatalogHotel> batch, int[] written, int[] bad) {
        try {
            tx.executeWithoutResult(t -> {
                jdbc.batchUpdate(UPSERT, batch.stream().map(h -> row(supplierId, h, start)).toList());
                beat(supplierId, runId);
            });
            written[0] += batch.size();
        } catch (DataAccessException e) {
            log.warn("Hotel catalogue batch failed, storing it row by row: {}", e.getMostSpecificCause().getMessage());
            for (CatalogHotel h : batch) {
                try {
                    tx.executeWithoutResult(t -> jdbc.update(UPSERT, row(supplierId, h, start)));
                    written[0]++;
                } catch (DataAccessException rowError) {
                    bad[0]++;
                }
            }
            tx.executeWithoutResult(t -> beat(supplierId, runId));
        }
    }

    private void beat(String supplierId, UUID runId) {
        if (jdbc.update("UPDATE public.hotel_catalog_sync SET heartbeat_at = ? WHERE supplier_id = ? AND run_id = ?",
                ts(clock.instant()), supplierId, runId) == 0) {
            throw new IllegalStateException("Another load took this supplier's catalogue over");
        }
    }

    private void fail(ResolvedCredential supplier, UUID runId, String reason) {
        String shortReason = reason.length() <= 300 ? reason : reason.substring(0, 300);
        log.warn("Hotel catalogue load failed for {}, its hotels are kept: {}", supplier.name(), shortReason);
        jdbc.update("UPDATE public.hotel_catalog_sync SET status = 'FAILED', finished_at = ?, last_error = ? WHERE supplier_id = ? AND run_id = ?",
                ts(clock.instant()), shortReason, supplier.id(), runId);
    }

    private static Object[] row(String supplierId, CatalogHotel h, Instant start) {
        return new Object[]{supplierId, h.hotelId(), h.name(), h.city(), h.countryCode(), h.countryName(), h.starRating(), h.propertyType(), ts(start)};
    }

    /** Every supplier that has had a load: its state and how many hotels it holds right now. */
    public List<HotelCatalogStatusResponse> status() {
        return jdbc.query("SELECT c.id, c.name, s.status, s.started_at, s.finished_at, s.last_done_at, s.last_error, "
                + "(SELECT count(*) FROM public.hotel_catalog h WHERE h.supplier_id = c.id) AS stored "
                + "FROM public.hotel_catalog_sync s JOIN public.platform_supplier_credential c ON c.id = s.supplier_id ORDER BY c.name",
                (rs, i) -> new HotelCatalogStatusResponse(rs.getString("id"), rs.getString("name"), rs.getString("status"), rs.getLong("stored"),
                        instant(rs, "started_at"), instant(rs, "finished_at"), instant(rs, "last_done_at"), rs.getString("last_error")));
    }

    /**
     * Up to 8 hotels whose name holds the typed text, from suppliers with hotel search on: names starting with it
     * first, then the closest, then the best rated. Fewer than 3 characters asks nothing. Best effort - a slow or
     * failed lookup offers no hotels rather than an error, so the city list still works.
     */
    public List<HotelSuggestionResponse> suggest(String q) {
        String text = q == null ? "" : q.strip();
        if (text.length() < 3) return List.of();
        if (text.length() > 60) text = text.substring(0, 60);
        Map<String, String> names = registry.searchable(PricingProduct.HOTEL).stream()
                .collect(Collectors.toMap(ResolvedCredential::id, ResolvedCredential::name, (a, b) -> a));
        if (names.isEmpty()) return List.of();
        String escaped = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        String sql = "SELECT supplier_id, hotel_id, name, city, country_code, country_name, star_rating FROM public.hotel_catalog "
                + "WHERE supplier_id IN (" + String.join(", ", Collections.nCopies(names.size(), "?")) + ") AND name ILIKE ? ESCAPE '\\' "
                + "ORDER BY (name ILIKE ? ESCAPE '\\') DESC, public.word_similarity(?, name) DESC, star_rating DESC NULLS LAST, name LIMIT " + SUGGESTIONS;
        Object[] args = java.util.stream.Stream.concat(names.keySet().stream(),
                java.util.stream.Stream.of("%" + escaped + "%", escaped + "%", text)).toArray();
        try {
            return tx.execute(t -> {
                jdbc.execute("SET LOCAL statement_timeout = '500ms'");
                return jdbc.query(sql, (rs, i) -> suggestion(rs, names), args);
            });
        } catch (DataAccessException e) {
            log.warn("Hotel name suggestions failed: {}", e.getMostSpecificCause().getMessage());
            return List.of();
        }
    }

    private static HotelSuggestionResponse suggestion(ResultSet rs, Map<String, String> names) throws SQLException {
        String supplierId = rs.getString("supplier_id");
        Integer stars = rs.getObject("star_rating", Integer.class);
        return new HotelSuggestionResponse(SupplierIds.wrap(supplierId, rs.getString("hotel_id")), rs.getString("name"), rs.getString("city"),
                rs.getString("country_code"), rs.getString("country_name"), stars, names.get(supplierId));
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
