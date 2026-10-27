package com.voyra.crm.controller;

import com.voyra.crm.dto.AgencyPricingAssignRequest;
import com.voyra.crm.dto.AgencyPricingAssignmentResponse;
import com.voyra.crm.dto.PricingPreviewResponse;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.service.AgencyPricingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/platform/agencies/{id}/pricing")
@RequiredArgsConstructor
@Tag(name = "Platform - Agency pricing", description = "SUPER_ADMIN: which discount and commission rule an agency is on, per product")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class PlatformAgencyPricingController {

    private final AgencyPricingService service;

    @GetMapping
    @Operation(summary = "The agency's discount and commission rule for FLIGHT and HOTEL (Default rules when unassigned)")
    public ResponseEntity<List<AgencyPricingAssignmentResponse>> assignments(@PathVariable String id) {
        return ResponseEntity.ok(service.assignments(id));
    }

    @GetMapping("/preview")
    @Operation(summary = "Preview a discount and commission rule pair against the reference fare basket")
    public ResponseEntity<PricingPreviewResponse> preview(@PathVariable String id, @RequestParam PricingProduct product,
                                                          @RequestParam String discountRuleId, @RequestParam String commissionRuleId) {
        return ResponseEntity.ok(service.preview(id, product, discountRuleId, commissionRuleId));
    }

    @PutMapping("/{product}")
    @Operation(summary = "Put the agency on a discount and a commission rule for a product",
            description = "Takes effect at once (next booking review). Audited with the worst case and lossMaking.")
    public ResponseEntity<AgencyPricingAssignmentResponse> assign(@PathVariable String id, @PathVariable PricingProduct product,
                                                                  @Valid @RequestBody AgencyPricingAssignRequest request) {
        return ResponseEntity.ok(service.assign(id, product, request));
    }
}
