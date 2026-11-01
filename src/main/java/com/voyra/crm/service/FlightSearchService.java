package com.voyra.crm.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.voyra.crm.dto.FlightGroupResponse;
import com.voyra.crm.dto.FlightOfferResponse;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.exception.BookingConflictException;
import com.voyra.crm.exception.SuppliersUnavailableException;
import com.voyra.crm.models.ResolvedCredential;
import com.voyra.crm.service.booking.AgencyPriceQuoter;
import com.voyra.crm.service.booking.SupplierRegistry;
import com.voyra.crm.util.SupplierIds;
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
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Paged, sorted, filtered flight search. The supplier answers a search in one call with every
 * flight; paging does not make that call faster, it stops the browser receiving and rendering
 * hundreds of fares at once. So the full result is cached briefly per agency and search, and a
 * page, sort or filter change is served from that cache with no second supplier call.
 *
 * <p>The cache key includes the tenant, because fares are priced for each agency,
 * so one agency must never be served another's prices. It is an in-memory cache per instance;
 * if a request lands on an instance that has not seen the search it simply re-queries the supplier,
 * so a miss costs time, never correctness.
 */
@Slf4j
@Service
public class FlightSearchService {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final long MAX_SEARCHES = 200;

    private final Cache<String, List<FlightGroupResponse>> cache = Caffeine.newBuilder()
            .expireAfterWrite(TTL)
            .maximumSize(MAX_SEARCHES)
            .build();

    private final SupplierRegistry registry;
    private final SupplierFanOut fanOut;
    private final AgencyPriceQuoter quoter;

    public FlightSearchService(SupplierRegistry registry, SupplierFanOut fanOut, AgencyPriceQuoter quoter) {
        this.registry = registry;
        this.fanOut = fanOut;
        this.quoter = quoter;
    }

    /** Offers from every supplier with flight search on, priced for the caller's agency; a platform admin (no tenant) gets them undecorated. */
    public List<FlightOfferResponse> search(FlightSearchQuery query) {
        return priced(SecurityContextUtil.getCurrentUserOrThrow().tenantId(),
                ask(query, registry.searchable(PricingProduct.FLIGHT)).answers());
    }

    /** Asks every supplier at once; 409 when none is on, 503 when none answered. Each offer is stamped with its supplier and its ids namespaced by it. */
    private SupplierFanOut.Result<FlightOfferResponse> ask(FlightSearchQuery query, List<ResolvedCredential> suppliers) {
        if (suppliers.isEmpty()) {
            throw new BookingConflictException("NO_SUPPLIER", "No supplier is enabled for flight search", Map.of());
        }
        SupplierFanOut.Result<FlightOfferResponse> result = fanOut.run(suppliers, s -> {
            List<FlightOfferResponse> offers = registry.flightSearch(s.provider()).search(query, s);
            offers.forEach(o -> stamp(o, s));
            return offers;
        });
        if (result.failed().size() == suppliers.size()) {
            throw new SuppliersUnavailableException();
        }
        return result;
    }

    private static void stamp(FlightOfferResponse o, ResolvedCredential s) {
        o.setSupplierId(s.id());
        o.setSupplierName(s.name());
        o.setSupplier(s.name());
        o.setOfferId(SupplierIds.wrap(s.id(), o.getOfferId()));
        o.setSpecialReturnId(SupplierIds.wrap(s.id(), o.getSpecialReturnId()));
        if (o.getPairsWith() != null) {
            o.setPairsWith(o.getPairsWith().stream().map(id -> SupplierIds.wrap(s.id(), id)).toList());
        }
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
        List<ResolvedCredential> suppliers = registry.searchable(PricingProduct.FLIGHT);
        String key = cacheKey(tenantId, req.getQuery(), suppliers);
        List<FlightGroupResponse> groups = req.isRefresh() ? null : cache.getIfPresent(key);
        List<String> failed = List.of();
        if (groups == null) {
            long startedAt = System.currentTimeMillis();
            SupplierFanOut.Result<FlightOfferResponse> result = ask(req.getQuery(), suppliers);
            groups = FlightResultPaging.group(priced(tenantId, result.answers()));
            failed = result.failed();
            if (failed.isEmpty()) {
                cache.put(key, groups);
            }
            log.info("Flight search cache MISS: {} flights from {} suppliers in {} ms (refresh={}, failed={})",
                    groups.size(), suppliers.size(), System.currentTimeMillis() - startedAt, req.isRefresh(), failed);
        } else {
            log.debug("Flight search cache HIT: {} flights", groups.size());
        }
        FlightSearchPageResponse page = FlightResultPaging.page(groups, req, registry.bookingEnabledIds(PricingProduct.FLIGHT));
        page.setSuppliersFailed(failed);
        return page;
    }

    /** A toggle or an edit of a supplier changes the key, so it never serves fares of a supplier that was switched off. */
    static String cacheKey(String tenantId, FlightSearchQuery q, List<ResolvedCredential> suppliers) {
        return String.join("|", String.valueOf(tenantId), q.getOrigin(), q.getDestination(),
                String.valueOf(q.getDepartDate()), String.valueOf(q.getReturnDate()), String.valueOf(q.getCabin()),
                String.valueOf(q.getAdults()), String.valueOf(q.getChildren()), String.valueOf(q.getInfants()),
                suppliers.stream().map(s -> s.id() + "@" + s.updatedAt()).sorted().collect(Collectors.joining(",")));
    }
}
