package com.voyra.crm.controller;

import com.voyra.crm.dto.AgencyPricingCreateRequest;
import com.voyra.crm.dto.AgencyPricingResponse;
import com.voyra.crm.dto.PricingPreviewResponse;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.service.AgencyPricingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/platform/agencies/{id}/pricing")
@RequiredArgsConstructor
@Tag(name = "Platform - Agency pricing", description = "SUPER_ADMIN per-agency discount and commission rates")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class PlatformAgencyPricingController {

    private final AgencyPricingService service;

    @GetMapping
    @Operation(summary = "Rate history for an agency, newest effective date first")
    public ResponseEntity<List<AgencyPricingResponse>> history(@PathVariable String id,
                                                               @RequestParam(required = false) PricingProduct product) {
        return ResponseEntity.ok(service.history(id, product));
    }

    @GetMapping("/preview")
    @Operation(summary = "Preview a rate against the reference fare basket")
    public ResponseEntity<PricingPreviewResponse> preview(@PathVariable String id,
                                                          @RequestParam PricingProduct product,
                                                          @RequestParam BigDecimal discountPct,
                                                          @RequestParam BigDecimal commissionPct,
                                                          @RequestParam(defaultValue = "false") boolean allowCommissionAboveEarned) {
        return ResponseEntity.ok(service.preview(id, product, discountPct, commissionPct, allowCommissionAboveEarned));
    }

    @PostMapping
    @Operation(summary = "Add a rate; loss-making rates wait for a second admin")
    public ResponseEntity<AgencyPricingResponse> create(@PathVariable String id,
                                                        @Valid @RequestBody AgencyPricingCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(id, request));
    }

    @PostMapping("/{pricingId}/approve")
    @Operation(summary = "Approve a rate as a second platform admin")
    public ResponseEntity<AgencyPricingResponse> approve(@PathVariable String id, @PathVariable String pricingId) {
        return ResponseEntity.ok(service.approve(id, pricingId));
    }
}
