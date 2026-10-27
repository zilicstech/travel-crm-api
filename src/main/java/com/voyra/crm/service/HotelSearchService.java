package com.voyra.crm.service;

import com.voyra.crm.context.TenantContext;
import com.voyra.crm.dto.HotelOfferResponse;
import com.voyra.crm.dto.HotelSearchPageResponse;
import com.voyra.crm.dto.HotelSearchQuery;
import com.voyra.crm.dto.HotelDetailResponse;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.service.booking.AgencyPriceQuoter;
import com.voyra.crm.service.booking.PriceQuote;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
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
 * A result belongs to the agency that ran it (its own supplier rates); an expired or unknown searchId searches anew.
 * Offers and room options are priced for that agency once, when the result is kept, so the cache is tenant-keyed by
 * construction; a platform admin (no tenant) gets them undecorated.
 */
@Service
@RequiredArgsConstructor
public class HotelSearchService {

    static final int PAGE_SIZE = 20;
    private static final Duration KEEP = Duration.ofMinutes(30);
    static final int MAX_KEPT = 200;

    private record Result(String tenantId, List<HotelOfferResponse> offers, Instant at) { }

    private final HotelSearchProvider hotelSearchProvider;
    private final AgencyPriceQuoter quoter;
    private final Map<String, Result> results = new ConcurrentHashMap<>();

    public HotelSearchPageResponse search(HotelSearchQuery query) {
        String tenantId = TenantContext.getTenantId();
        String searchId = query.getSearchId();
        Result result = searchId == null ? null : results.get(searchId);
        if (result == null || !Objects.equals(result.tenantId(), tenantId) || result.at().plus(KEEP).isBefore(Instant.now())) {
            results.values().removeIf(r -> r.at().plus(KEEP).isBefore(Instant.now()));
            // Bounded: a burst of searches drops the oldest kept result rather than growing without limit.
            if (results.size() >= MAX_KEPT) {
                results.entrySet().stream().min(Map.Entry.comparingByValue(java.util.Comparator.comparing(Result::at)))
                        .ifPresent(e -> results.remove(e.getKey()));
            }
            searchId = UUID.randomUUID().toString();
            result = new Result(tenantId, priced(tenantId, hotelSearchProvider.search(query)), Instant.now());
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
                .build();
    }

    /** One hotel's page with each room option priced for the caller's agency. */
    public HotelDetailResponse detail(String hotelId, HotelSearchQuery query) {
        HotelDetailResponse d = hotelSearchProvider.detail(hotelId, query);
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
