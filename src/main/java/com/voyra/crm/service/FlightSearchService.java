package com.voyra.crm.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.voyra.crm.dto.FlightGroupResponse;
import com.voyra.crm.dto.FlightOfferResponse;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.service.booking.AgencyPriceQuoter;
import com.voyra.crm.service.booking.PriceQuote;
import com.voyra.crm.dto.FlightSearchPageRequest;
import com.voyra.crm.dto.FlightSearchPageResponse;
import com.voyra.crm.dto.FlightSearchQuery;
import com.voyra.crm.security.SecurityContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Paged, sorted, filtered flight search. The supplier answers a search in one call with every
 * flight; paging does not make that call faster, it stops the browser receiving and rendering
 * hundreds of fares at once. So the full result is cached briefly per agency and search, and a
 * page, sort or filter change is served from that cache with no second supplier call.
 *
 * <p>The cache key includes the tenant: fares come from each agency's own supplier credentials,
 * so one agency must never be served another's prices. It is an in-memory cache per instance;
 * if a request lands on an instance that has not seen the search it simply re-queries the supplier,
 * so a miss costs time, never correctness.
 */
@Slf4j
@Service
public class FlightSearchService {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final long MAX_SEARCHES = 200;

    private final FlightSearchProvider provider;
    private final Cache<String, List<FlightGroupResponse>> cache = Caffeine.newBuilder()
            .expireAfterWrite(TTL)
            .maximumSize(MAX_SEARCHES)
            .build();

    private final AgencyPriceQuoter quoter;

    public FlightSearchService(FlightSearchProvider provider, AgencyPriceQuoter quoter) {
        this.provider = provider;
        this.quoter = quoter;
    }

    /** Supplier offers priced for the caller's agency; a platform admin (no tenant) gets them undecorated. */
    public List<FlightOfferResponse> search(FlightSearchQuery query) {
        return priced(SecurityContextUtil.getCurrentUserOrThrow().tenantId(), provider.search(query));
    }

    private List<FlightOfferResponse> priced(String tenantId, List<FlightOfferResponse> offers) {
        if (tenantId == null) {
            return offers;
        }
        Optional<BiFunction<BigDecimal, BigDecimal, PriceQuote>> quote = quoter.quoterFor(tenantId, PricingProduct.FLIGHT);
        for (FlightOfferResponse o : offers) {
            BigDecimal gross = o.getTotalPrice() != null ? o.getTotalPrice() : o.getPrice();
            o.setPublishedFare(gross);
            boolean hasBase = o.getBaseFare() != null && o.getBaseFare().signum() > 0;
            if (quote.isPresent() && hasBase) {
                PriceQuote q = quote.get().apply(gross, o.getBaseFare());
                o.setDisplayedFare(q.displayed());
                o.setAgencyEarns(q.agencyEarns());
                o.setBookable(true);
            } else {
                o.setDisplayedFare(gross);
                o.setAgencyEarns(BigDecimal.ZERO);
                o.setBookable(false);
                o.setNotBookableReason(quote.isPresent() ? "BASE_FARE_UNAVAILABLE" : "PRICING_NOT_CONFIGURED");
            }
        }
        return offers;
    }

    public FlightSearchPageResponse searchPage(FlightSearchPageRequest req) {
        String tenantId = SecurityContextUtil.getCurrentUserOrThrow().tenantId();
        String key = cacheKey(tenantId, req.getQuery());
        List<FlightGroupResponse> groups = req.isRefresh() ? null : cache.getIfPresent(key);
        if (groups == null) {
            long startedAt = System.currentTimeMillis();
            groups = FlightResultPaging.group(priced(tenantId, provider.search(req.getQuery())));
            cache.put(key, groups);
            log.info("Flight search cache MISS: {} flights from supplier in {} ms (refresh={})",
                    groups.size(), System.currentTimeMillis() - startedAt, req.isRefresh());
        } else {
            log.debug("Flight search cache HIT: {} flights", groups.size());
        }
        return FlightResultPaging.page(groups, req);
    }

    static String cacheKey(String tenantId, FlightSearchQuery q) {
        return String.join("|", String.valueOf(tenantId), q.getOrigin(), q.getDestination(),
                String.valueOf(q.getDepartDate()), String.valueOf(q.getReturnDate()), String.valueOf(q.getCabin()),
                String.valueOf(q.getAdults()), String.valueOf(q.getChildren()), String.valueOf(q.getInfants()));
    }
}
