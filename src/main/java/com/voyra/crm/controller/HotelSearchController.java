package com.voyra.crm.controller;

import com.voyra.crm.dto.HotelOfferResponse;
import com.voyra.crm.dto.HotelSearchQuery;
import com.voyra.crm.service.HotelSearchProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Supplier hotel search - the same shape as FlightSearchController. A search is stateless
 * and only ever feeds a proposal-item batch create afterward.
 */
@Slf4j
@RestController
@RequestMapping("/api/hotels")
@RequiredArgsConstructor
@Tag(name = "Hotels", description = "Supplier hotel search feeding the proposal builder")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT')")
public class HotelSearchController {

    private final HotelSearchProvider hotelSearchProvider;

    @PostMapping("/search")
    @Operation(summary = "Search hotel offers", description = "Results are not persisted - "
            + "add the chosen ones to a service's proposal via POST /api/leads/{id}/proposal-items/batch.")
    public ResponseEntity<List<HotelOfferResponse>> search(@Valid @RequestBody HotelSearchQuery query) {
        return ResponseEntity.ok(hotelSearchProvider.search(query));
    }

    @PostMapping("/{hotelId}/detail")
    @Operation(summary = "Open one hotel from a search", description = "Photos, description, amenities, policies and every "
            + "bookable room option for the searched dates and guests. Send the search again with its correlationId.")
    public ResponseEntity<com.voyra.crm.dto.HotelDetailResponse> detail(@PathVariable String hotelId, @Valid @RequestBody HotelSearchQuery query) {
        return ResponseEntity.ok(hotelSearchProvider.detail(hotelId, query));
    }

    @PostMapping("/thumbnails")
    @Operation(summary = "Photos, stars and address for listed hotels", description = "Asked after a search is shown, a page "
            + "of hotel ids at a time, so the list never waits on the supplier's content call.")
    public ResponseEntity<List<com.voyra.crm.dto.HotelThumbnailResponse>> thumbnails(@Valid @RequestBody com.voyra.crm.dto.HotelThumbnailsRequest request) {
        return ResponseEntity.ok(hotelSearchProvider.thumbnails(request.getHotelIds()));
    }
}
