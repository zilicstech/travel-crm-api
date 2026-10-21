package com.voyra.crm.service;

import com.voyra.crm.context.TenantContext;
import com.voyra.crm.dto.HotelOfferResponse;
import com.voyra.crm.dto.HotelSearchPageResponse;
import com.voyra.crm.dto.HotelSearchQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pages a hotel search 20 at a time. The supplier prices every hotel of a city in one go, so the whole result is
 * kept in memory for 30 minutes under a random searchId and later pages are sliced from it - never searched again.
 * A result belongs to the agency that ran it (its own supplier rates); an expired or unknown searchId searches anew.
 */
@Service
@RequiredArgsConstructor
public class HotelSearchService {

    static final int PAGE_SIZE = 20;
    private static final Duration KEEP = Duration.ofMinutes(30);
    static final int MAX_KEPT = 200;

    private record Result(String tenantId, List<HotelOfferResponse> offers, Instant at) { }

    private final HotelSearchProvider hotelSearchProvider;
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
            result = new Result(tenantId, hotelSearchProvider.search(query), Instant.now());
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
}
