package com.voyra.crm.controller;

import com.voyra.crm.dto.VisaChecklistUpdateRequest;
import com.voyra.crm.dto.VisaCreateRequest;
import com.voyra.crm.dto.VisaDashboardSummaryResponse;
import com.voyra.crm.dto.VisaResponse;
import com.voyra.crm.service.VisaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Standalone Visa case management (the Owner's dedicated Visa dashboard) - distinct from the
 * lightweight 5-step visa tracker embedded on a Lead (see LeadController's /visa-tracker).
 */
@Slf4j
@RestController
@RequestMapping("/api/visas")
@RequiredArgsConstructor
@Tag(name = "Visas", description = "Standalone visa case tracking and pipeline dashboard")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT')")
public class VisaController {

    private final VisaService visaService;

    @PostMapping
    @Operation(summary = "Open a new visa case")
    public ResponseEntity<VisaResponse> createVisa(@Valid @RequestBody VisaCreateRequest request) {
        return ResponseEntity.ok(visaService.createVisa(request));
    }

    @GetMapping
    @Operation(summary = "List visa cases", description = "Agents see only their own cases; Owners see the whole agency.")
    public ResponseEntity<List<VisaResponse>> listVisas() {
        return ResponseEntity.ok(visaService.listVisas());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Visa case detail")
    public ResponseEntity<VisaResponse> getVisa(@PathVariable String id) {
        return ResponseEntity.ok(visaService.getVisa(id));
    }

    @PatchMapping("/{id}/checklist")
    @Operation(summary = "Update the visa checklist", description = "Status is always server-derived from the checklist booleans.")
    public ResponseEntity<VisaResponse> updateChecklist(@PathVariable String id,
                                                         @Valid @RequestBody VisaChecklistUpdateRequest request) {
        return ResponseEntity.ok(visaService.updateChecklist(id, request));
    }

    @GetMapping("/dashboard-summary")
    @Operation(summary = "Visa pipeline KPI summary")
    public ResponseEntity<VisaDashboardSummaryResponse> getDashboardSummary() {
        return ResponseEntity.ok(visaService.getDashboardSummary());
    }
}
