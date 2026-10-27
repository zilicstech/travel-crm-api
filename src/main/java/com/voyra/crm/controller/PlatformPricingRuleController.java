package com.voyra.crm.controller;

import com.voyra.crm.dto.PricingRuleCreateRequest;
import com.voyra.crm.dto.PricingRuleImpactResponse;
import com.voyra.crm.dto.PricingRuleResponse;
import com.voyra.crm.dto.PricingRuleUpdateRequest;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.service.AgencyPricingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/platform/pricing-rules")
@RequiredArgsConstructor
@Tag(name = "Platform - Pricing rules", description = "SUPER_ADMIN shared discount and commission rules per product")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class PlatformPricingRuleController {

    private final AgencyPricingService service;

    @GetMapping
    @Operation(summary = "Rules, Default first, with how many agencies are on each")
    public ResponseEntity<List<PricingRuleResponse>> list(@RequestParam(required = false) PricingProduct product) {
        return ResponseEntity.ok(service.listRules(product));
    }

    @PostMapping
    @Operation(summary = "Create a discount or commission rule", description = "409 when the name is taken for that kind and product. Audited.")
    public ResponseEntity<PricingRuleResponse> create(@Valid @RequestBody PricingRuleCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createRule(request));
    }

    @PutMapping("/{ruleId}")
    @Operation(summary = "Edit a rule's name and value", description = "Applies at once to every agency on it, from their next booking review. Audited.")
    public ResponseEntity<PricingRuleResponse> update(@PathVariable String ruleId, @Valid @RequestBody PricingRuleUpdateRequest request) {
        return ResponseEntity.ok(service.updateRule(ruleId, request));
    }

    @DeleteMapping("/{ruleId}")
    @Operation(summary = "Delete an unused rule", description = "409 RULE_IN_USE while any agency is on it; 409 DEFAULT_RULE for a Default rule.")
    public ResponseEntity<Void> delete(@PathVariable String ruleId) {
        service.deleteRule(ruleId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{ruleId}/impact")
    @Operation(summary = "Which agencies a proposed value reaches, and which it would make loss-making")
    public ResponseEntity<PricingRuleImpactResponse> impact(@PathVariable String ruleId, @RequestParam BigDecimal pct,
                                                            @RequestParam(defaultValue = "false") boolean allowAboveEarned) {
        return ResponseEntity.ok(service.impact(ruleId, pct, allowAboveEarned));
    }
}
