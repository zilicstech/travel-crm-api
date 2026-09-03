package com.voyra.crm.controller;

import com.voyra.crm.dto.ServiceAssignRequest;
import com.voyra.crm.dto.ServiceDraft;
import com.voyra.crm.dto.ServicePreferenceToggleRequest;
import com.voyra.crm.dto.ServiceResponse;
import com.voyra.crm.dto.ServiceStatusUpdateRequest;
import com.voyra.crm.dto.VisaChecklistToggleRequest;
import com.voyra.crm.service.ServiceInstanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * One lead's service instances - Flight/Hotel/Visa/Transfer, any number of each. Fine-grained
 * access (owner, type-matching agent, or the agent already assigned) is enforced inside
 * {@link ServiceInstanceService}, not by role alone, since who may touch a service depends on
 * its type and its assignee, not on being an AGENT at all.
 */
@Slf4j
@RestController
@RequestMapping("/api/leads/{leadId}/services")
@RequiredArgsConstructor
@Tag(name = "Lead Services", description = "Per-instance Flight/Hotel/Visa/Transfer services on a lead")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT')")
public class LeadServiceController {

    private final ServiceInstanceService serviceInstanceService;

    @PostMapping
    @Operation(summary = "Add one or more service instances to the lead",
            description = "Always creates NEW instances - the same call whether it is the lead's first flight or its fourth.")
    public ResponseEntity<List<ServiceResponse>> addServices(@PathVariable String leadId,
                                                              @RequestBody List<@Valid ServiceDraft> drafts) {
        return ResponseEntity.ok(serviceInstanceService.createServices(leadId, drafts));
    }

    @GetMapping
    @Operation(summary = "This lead's services")
    public ResponseEntity<List<ServiceResponse>> listServices(@PathVariable String leadId) {
        return ResponseEntity.ok(serviceInstanceService.listForLead(leadId));
    }

    @PutMapping("/{serviceId}")
    @Operation(summary = "Replace one service instance's fields")
    public ResponseEntity<ServiceResponse> updateService(@PathVariable String leadId,
                                                          @PathVariable String serviceId,
                                                          @Valid @RequestBody ServiceDraft draft) {
        return ResponseEntity.ok(serviceInstanceService.updateService(leadId, serviceId, draft));
    }

    @PatchMapping("/{serviceId}/status")
    @Operation(summary = "Change a service's status",
            description = "Only the owner or the agent already assigned - and only once assigned at all.")
    public ResponseEntity<ServiceResponse> setStatus(@PathVariable String leadId, @PathVariable String serviceId,
                                                      @Valid @RequestBody ServiceStatusUpdateRequest request) {
        return ResponseEntity.ok(serviceInstanceService.setStatus(leadId, serviceId, request));
    }

    @PostMapping("/{serviceId}/accept")
    @Operation(summary = "Claim an unassigned service",
            description = "Puts the calling agent's name on it and moves it to IN_PROGRESS in one step.")
    public ResponseEntity<ServiceResponse> acceptService(@PathVariable String leadId, @PathVariable String serviceId) {
        return ResponseEntity.ok(serviceInstanceService.acceptService(leadId, serviceId));
    }

    @PatchMapping("/{serviceId}/assign")
    @PreAuthorize("hasRole('AGENCY_OWNER')")
    @Operation(summary = "Reassign a service to a different agent", description = "Owner-only.")
    public ResponseEntity<ServiceResponse> assignService(@PathVariable String leadId, @PathVariable String serviceId,
                                                          @Valid @RequestBody ServiceAssignRequest request) {
        return ResponseEntity.ok(serviceInstanceService.assignService(leadId, serviceId, request));
    }

    @PatchMapping("/{serviceId}/preferences")
    @Operation(summary = "Toggle one standing preference", description = "Writes immediately, no edit-mode round trip.")
    public ResponseEntity<ServiceResponse> togglePreference(@PathVariable String leadId, @PathVariable String serviceId,
                                                             @Valid @RequestBody ServicePreferenceToggleRequest request) {
        return ResponseEntity.ok(serviceInstanceService.togglePreference(leadId, serviceId, request));
    }

    @PatchMapping("/{serviceId}/visa-checklist")
    @Operation(summary = "Toggle one step of one traveller's visa checklist on this service")
    public ResponseEntity<ServiceResponse> toggleVisaChecklist(@PathVariable String leadId, @PathVariable String serviceId,
                                                                @Valid @RequestBody VisaChecklistToggleRequest request) {
        return ResponseEntity.ok(serviceInstanceService.toggleVisaChecklist(leadId, serviceId, request));
    }

    @DeleteMapping("/{serviceId}")
    @Operation(summary = "Remove a service instance",
            description = "A hard delete, for genuine entry mistakes - cancelled work is expressed by status instead.")
    public ResponseEntity<Void> deleteService(@PathVariable String leadId, @PathVariable String serviceId) {
        serviceInstanceService.deleteService(leadId, serviceId);
        return ResponseEntity.noContent().build();
    }
}
