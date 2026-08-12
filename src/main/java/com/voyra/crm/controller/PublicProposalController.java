package com.voyra.crm.controller;

import com.voyra.crm.dto.PublicProposalResponse;
import com.voyra.crm.dto.SimpleAckResponse;
import com.voyra.crm.service.PublicProposalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unauthenticated, customer-facing. "Not found" (unknown/expired token) surfaces through the
 * same IllegalArgumentException -> 400 path as everywhere else in the API (blueprint §6.1) -
 * no bespoke 404 handling here, and the message never reveals whether a token ever existed.
 * Never returns a mapped entity - see {@link com.voyra.crm.service.PublicProposalTenantService}
 * for the narrow DTO construction that keeps this endpoint pricing-safe by construction.
 */
@Slf4j
@RestController
@RequestMapping("/api/public/proposals")
@RequiredArgsConstructor
@Tag(name = "Public - Proposals", description = "Unauthenticated customer-facing proposal view")
public class PublicProposalController {

    private final PublicProposalService publicProposalService;

    @GetMapping("/{token}")
    @Operation(summary = "View a shared proposal", description = "No auth required. Pricing-safe by construction - never exposes net cost or margin.")
    public ResponseEntity<PublicProposalResponse> getProposal(@PathVariable String token) {
        return ResponseEntity.ok(publicProposalService.getProposal(token));
    }

    @PostMapping("/{token}/approve")
    @Operation(summary = "Approve a proposal", description = "No auth required. Idempotent - transitions the lead toward Negotiating.")
    public ResponseEntity<SimpleAckResponse> approveProposal(@PathVariable String token) {
        publicProposalService.approveProposal(token);
        return ResponseEntity.ok(SimpleAckResponse.builder().success(true).message("Proposal approved").build());
    }
}
