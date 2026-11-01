package com.voyra.crm.service;

import com.voyra.crm.context.TenantContext;
import com.voyra.crm.dto.HotelOfferResponse;
import com.voyra.crm.dto.HotelSearchPageResponse;
import com.voyra.crm.dto.HotelSearchQuery;
import com.voyra.crm.dto.HotelDetailResponse;
import com.voyra.crm.dto.HotelThumbnailResponse;
import com.voyra.crm.exception.BookingConflictException;
import com.voyra.crm.exception.BookingRequestException;
import com.voyra.crm.exception.SuppliersUnavailableException;
import com.voyra.crm.models.ResolvedCredential;
import com.voyra.crm.service.booking.SupplierRegistry;
import com.voyra.crm.util.SupplierIds;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.service.booking.AgencyPriceQuoter;
import com.voyra.crm.service.booking.PriceQuote;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * Pages a hotel search 20 at a time. The supplier prices every hotel of a city in one go, so the whole result is
 * kept in memory for 30 minutes under a random searchId and later pages are sliced from it - never searched again.
 * Every supplier with hotel search on is asked at once and a hotel two suppliers both list stays two rows (ids
 * namespaced by supplier), cheapest first; a partial result is kept with the names that did not answer, so later pages
 * slice the same list. A result belongs to the agency that ran it (its own supplier rates); an expired or unknown searchId searches anew.
 * Offers and room options are priced for that agency once, when the result is kept, so the cache is tenant-keyed by
 * construction; a platform admin (no tenant) gets them undecorated.
 */
@Service
@RequiredArgsConstructor
public class HotelSearchService {

    static final int PAGE_SIZE = 20;
    private static final Duration KEEP = Duration.ofMinutes(30);
    static final int MAX_KEPT = 200;

    private record Result(String tenantId, List<HotelOfferResponse> offers, List<String> failed, Instant at) { }

    private final SupplierRegistry registry;
    private final SupplierFanOut fanOut;
    private final AgencyPriceQuoter quoter;
    private final Map<String, Result> results = new ConcurrentHashMap<>();
    /** There is no Clock bean; tests set this to move time past the 30-minute keep. */
    Clock clock = Clock.systemUTC();

    public HotelSearchPageResponse search(HotelSearchQuery query) {
        String tenantId = TenantContext.getTenantId();
        String searchId = query.getSearchId();
        Result result = searchId == null ? null : results.get(searchId);
        Instant now = clock.instant();
        if (result == null || !Objects.equals(result.tenantId(), tenantId) || result.at().plus(KEEP).isBefore(now)) {
            results.values().removeIf(r -> r.at().plus(KEEP).isBefore(now));
            // Bounded: a burst of searches drops the oldest kept result rather than growing without limit.
            if (results.size() >= MAX_KEPT) {
                results.entrySet().stream().min(Map.Entry.comparingByValue(java.util.Comparator.comparing(Result::at)))
                        .ifPresent(e -> results.remove(e.getKey()));
            }
            searchId = UUID.randomUUID().toString();
            SupplierFanOut.Result<HotelOfferResponse> asked = ask(query);
            List<HotelOfferResponse> offers = priced(tenantId, asked.answers());
            offers.sort(Comparator.comparing(o -> o.getDisplayedFare() != null ? o.getDisplayedFare() : o.getPrice(),
                    Comparator.nullsLast(Comparator.naturalOrder())));
            result = new Result(tenantId, offers, asked.failed(), now);
            results.put(searchId, result);
        }
        int total = result.offers().size();
        int from = Math.min(query.getPage() * PAGE_SIZE, total);
        return HotelSearchPageResponse.builder()
                .searchId(searchId)
                .page(query.getPage())
                .pageSize(PAGE_SIZE)
                .total(total)
                .offers(result.offers().subList(from, Math.min(from + PAGE_SIZE, total)))
                .suppliersFailed(result.failed())
                .build();
    }

    /** Asks every supplier at once; 409 when none is on, 503 when none answered. Each offer is stamped with its supplier and its ids namespaced by it. */
    private SupplierFanOut.Result<HotelOfferResponse> ask(HotelSearchQuery query) {
        List<ResolvedCredential> suppliers = registry.searchable(PricingProduct.HOTEL);
        if (suppliers.isEmpty()) {
            throw new BookingConflictException("NO_SUPPLIER", "No supplier is enabled for hotel search", Map.of());
        }
        SupplierFanOut.Result<HotelOfferResponse> result = fanOut.run(suppliers, s -> {
            List<HotelOfferResponse> offers = new ArrayList<>(registry.hotelSearch(s.provider()).search(query, s));
            offers.forEach(o -> {
                o.setSupplierId(s.id());
                o.setSupplierName(s.name());
                o.setSupplier(s.name());
                o.setOfferId(SupplierIds.wrap(s.id(), o.getOfferId()));
                o.setHotelId(SupplierIds.wrap(s.id(), o.getHotelId()));
                o.setCorrelationId(SupplierIds.wrap(s.id(), o.getCorrelationId()));
            });
            return offers;
        });
        if (result.failed().size() == suppliers.size()) {
            throw new SuppliersUnavailableException();
        }
        return new SupplierFanOut.Result<>(new ArrayList<>(result.answers()), result.failed());
    }

    /** One hotel's page, from the hotel's own supplier, with each room option priced for the caller's agency. */
    public HotelDetailResponse detail(String hotelId, HotelSearchQuery query) {
        SupplierIds.Scoped scoped = SupplierIds.parse(hotelId).orElseThrow(HotelSearchService::unavailable);
        ResolvedCredential supplier = registry.searchable(PricingProduct.HOTEL).stream()
                .filter(s -> s.id().equals(scoped.supplierId())).findFirst().orElseThrow(HotelSearchService::unavailable);
        if (query.getCorrelationId() != null && !query.getCorrelationId().isBlank()) {
            SupplierIds.Scoped correlation = SupplierIds.parse(query.getCorrelationId()).orElseThrow(HotelSearchService::unavailable);
            if (!correlation.supplierId().equals(supplier.id())) {
                throw unavailable();
            }
            query.setCorrelationId(correlation.id());
        }
        HotelDetailResponse d = registry.hotelSearch(supplier.provider()).detail(scoped.id(), query, supplier);
        d.setHotelId(SupplierIds.wrap(supplier.id(), d.getHotelId()));
        d.setSupplierId(supplier.id());
        d.setSupplierName(supplier.name());
        d.setSupplier(supplier.name());
        d.setBookingEnabled(registry.bookingEnabledIds(PricingProduct.HOTEL).contains(supplier.id()));
        if (d.getOptions() != null) {
            d.getOptions().forEach(o -> o.setOptionId(SupplierIds.wrap(supplier.id(), o.getOptionId())));
        }
        String tenantId = TenantContext.getTenantId();
        if (tenantId != null && d.getOptions() != null) {
            Optional<BiFunction<BigDecimal, BigDecimal, PriceQuote>> quote = quoter.quoterFor(tenantId, PricingProduct.HOTEL);
            for (HotelDetailResponse.RoomOption o : d.getOptions()) {
                PriceQuote q = quote(quote, o.getPrice(), o.getBaseFare());
                o.setPublishedFare(o.getPrice());
                o.setDisplayedFare(q != null ? q.displayed() : o.getPrice());
                o.setAgencyEarns(q != null ? q.agencyEarns() : BigDecimal.ZERO);
                o.setBookable(q != null);
                o.setNotBookableReason(q != null ? null : reason(quote));
            }
        }
        return d;
    }

    /** Photo, stars and address for listed hotels, one call per supplier; a supplier that fails contributes nothing. */
    public List<HotelThumbnailResponse> thumbnails(List<String> hotelIds) {
        Map<String, List<String>> bySupplier = new LinkedHashMap<>();
        hotelIds.forEach(id -> SupplierIds.parse(id).ifPresent(s -> bySupplier.computeIfAbsent(s.supplierId(), k -> new ArrayList<>()).add(s.id())));
        List<ResolvedCredential> known = registry.searchable(PricingProduct.HOTEL).stream().filter(s -> bySupplier.containsKey(s.id())).toList();
        return fanOut.run(known, s -> registry.hotelSearch(s.provider()).thumbnails(bySupplier.get(s.id()), s).stream()
                .peek(t -> t.setHotelId(SupplierIds.wrap(s.id(), t.getHotelId()))).toList()).answers();
    }

    private static BookingRequestException unavailable() {
        return new BookingRequestException("OFFER_UNAVAILABLE", "This hotel is no longer available; search again");
    }

    private List<HotelOfferResponse> priced(String tenantId, List<HotelOfferResponse> offers) {
        if (tenantId == null) {
            return offers;
        }
        Optional<BiFunction<BigDecimal, BigDecimal, PriceQuote>> quote = quoter.quoterFor(tenantId, PricingProduct.HOTEL);
        for (HotelOfferResponse o : offers) {
            PriceQuote q = quote(quote, o.getPrice(), o.getBaseFare());
            o.setPublishedFare(o.getPrice());
            o.setDisplayedFare(q != null ? q.displayed() : o.getPrice());
            o.setAgencyEarns(q != null ? q.agencyEarns() : BigDecimal.ZERO);
            o.setBookable(q != null);
            o.setNotBookableReason(q != null ? null : reason(quote));
        }
        return offers;
    }

    /** Null when the option cannot be priced: no rate in force, or no base (never priced on a base of 0). */
    private static PriceQuote quote(Optional<BiFunction<BigDecimal, BigDecimal, PriceQuote>> quote, BigDecimal gross, BigDecimal base) {
        return quote.isPresent() && gross != null && base != null && base.signum() > 0 ? quote.get().apply(gross, base) : null;
    }

    private static String reason(Optional<?> quote) {
        return quote.isPresent() ? "BASE_FARE_UNAVAILABLE" : "PRICING_NOT_CONFIGURED";
    }
}
