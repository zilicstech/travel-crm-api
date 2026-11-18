package com.voyra.crm.controller;

import com.voyra.crm.dto.FlightFareRulesRequest;
import com.voyra.crm.dto.FlightFareRulesResponse;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import com.voyra.crm.dto.FlightFareDayResponse;
import com.voyra.crm.enums.FlightCabin;
import com.voyra.crm.service.FlightFareCalendarService;
import java.time.LocalDate;
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
    private final FlightFareCalendarService fareCalendarService;

    @GetMapping("/calendar")
    @Operation(summary = "Fare calendar of a route",
            description = "For each day in [from, to] (at most 62 days, never before today) that an earlier one-way, adults-only "
                    + "search of any agency priced, the cheapest fare per adult it found, after the caller agency's discount. "
                    + "Indicative: the fare is confirmed by a search. Days never searched are absent.")
    public ResponseEntity<List<FlightFareDayResponse>> calendar(@RequestParam String origin, @RequestParam String destination,
                                                                @RequestParam(required = false) FlightCabin cabin,
                                                                @RequestParam LocalDate from, @RequestParam LocalDate to) {
        return ResponseEntity.ok(fareCalendarService.read(origin, destination, cabin, from, to));
    }

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

    @PostMapping("/fare-rules")
    @Operation(summary = "Fare rules of a search offer",
            description = "Asks the offer's supplier for its fare rules before any review. fareRules is null when the supplier "
                    + "published none or did not answer. The supplier's SEARCH-flow answer is unverified live.")
    public ResponseEntity<FlightFareRulesResponse> fareRules(@Valid @RequestBody FlightFareRulesRequest request) {
        return ResponseEntity.ok(flightSearchService.fareRules(request.getOfferId()));
    }
}
