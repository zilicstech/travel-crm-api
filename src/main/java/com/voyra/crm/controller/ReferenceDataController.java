package com.voyra.crm.controller;

import com.voyra.crm.cache.AirportCache;
import com.voyra.crm.dto.AirportOptionResponse;
import com.voyra.crm.dto.CountryOptionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The bundled airport/city/country reference list behind every location picker (flight route,
 * hotel city, visa country, transfer pickup/drop-off). Fixed data, identical for every tenant -
 * see {@link AirportCache}'s javadoc for why this is a classpath CSV rather than a table.
 *
 * <p>Deliberately its own controller rather than riding on {@code TaxConfigController}: that one
 * is Owner/Accountant only, and an AGENT is exactly who needs these lists while building a
 * service. Deliberately not under {@code /api/public/**} either - this is agency tooling, not a
 * customer-facing surface.
 */
@Slf4j
@RestController
@RequestMapping("/api/reference")
@Tag(name = "Reference Data", description = "Bundled airport/city/country lookups for location pickers")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT', 'ACCOUNTANT')")
public class ReferenceDataController {

    // 4,133 rows change only when someone re-runs scripts/refresh-reference-data.py and
    // redeploys - safe for a browser to cache for a day and revalidate via ETag the rest of
    // the time, rather than re-downloading ~290KB on every session.
    private static final CacheControl CACHE_CONTROL = CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic();

    @GetMapping("/airports")
    @Operation(summary = "List airports", description = "Every airport with an IATA code and scheduled service - the data behind the flight route and hotel/transfer city pickers.")
    public ResponseEntity<List<AirportOptionResponse>> airports() {
        List<AirportOptionResponse> body = AirportCache.all().stream()
                .map(a -> AirportOptionResponse.builder()
                        .iata(a.iata())
                        .name(a.name())
                        .city(a.city())
                        .countryCode(a.countryCode())
                        .sizeRank(a.sizeRank())
                        .build())
                .toList();
        return ResponseEntity.ok().cacheControl(CACHE_CONTROL).body(body);
    }

    @GetMapping("/countries")
    @Operation(summary = "List countries", description = "ISO country list - the data behind the visa/country picker.")
    public ResponseEntity<List<CountryOptionResponse>> countries() {
        List<CountryOptionResponse> body = AirportCache.countries().entrySet().stream()
                .map(e -> CountryOptionResponse.builder().code(e.getKey()).name(e.getValue()).build())
                .toList();
        return ResponseEntity.ok().cacheControl(CACHE_CONTROL).body(body);
    }
}
