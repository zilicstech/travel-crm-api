package com.voyra.crm.controller;

import com.voyra.crm.dto.FlightOfferResponse;
import com.voyra.crm.dto.FlightSearchPageRequest;
import com.voyra.crm.dto.FlightSearchPageResponse;
import com.voyra.crm.dto.FlightSearchQuery;
import com.voyra.crm.service.FlightSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Supplier flight search - deliberately its own top-level surface rather than nested under
 * a lead, since a search is stateless and only ever feeds a proposal-item batch create
 * afterward. See FlightSearchProvider for the strategy behind the actual call out.
 */
@Slf4j
@RestController
@RequestMapping("/api/flights")
@RequiredArgsConstructor
@Tag(name = "Flights", description = "Supplier flight search feeding the proposal builder")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT')")
public class FlightSearchController {

    private final FlightSearchService flightSearchService;

    @PostMapping("/search")
    @Operation(summary = "Search flight offers", description = "Results are not persisted - "
            + "add the chosen ones to a service's proposal via POST /api/leads/{id}/proposal-items/batch.")
    public ResponseEntity<List<FlightOfferResponse>> search(@Valid @RequestBody FlightSearchQuery query) {
        return ResponseEntity.ok(flightSearchService.search(query));
    }

    @PostMapping("/search/paged")
    @Operation(summary = "Search flights, one page at a time",
            description = "Groups the supplier's fares by flight, sorts (cheapest first by default), filters and "
                    + "pages them. The full supplier result is cached briefly per agency and search, so paging, "
                    + "sorting and filtering after the first call do not hit the supplier again. "
                    + "Send refresh=true to force a fresh supplier search.")
    public ResponseEntity<FlightSearchPageResponse> searchPaged(@Valid @RequestBody FlightSearchPageRequest request) {
        return ResponseEntity.ok(flightSearchService.searchPage(request));
    }
}
