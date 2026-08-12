package com.voyra.crm.controller;

import com.voyra.crm.dto.LeadAssignRequest;
import com.voyra.crm.dto.LeadCreateRequest;
import com.voyra.crm.dto.LeadDetailResponse;
import com.voyra.crm.dto.LeadFollowUpUpdateRequest;
import com.voyra.crm.dto.LeadNoteCreateRequest;
import com.voyra.crm.dto.LeadNoteResponse;
import com.voyra.crm.dto.LeadResponse;
import com.voyra.crm.dto.LeadStatusUpdateRequest;
import com.voyra.crm.dto.ProposalItemCreateRequest;
import com.voyra.crm.dto.ProposalItemResponse;
import com.voyra.crm.dto.ProposalLinkResponse;
import com.voyra.crm.dto.VisaTrackerUpdateRequest;
import com.voyra.crm.enums.LeadStatus;
import com.voyra.crm.service.LeadService;
import com.voyra.crm.service.ProposalLinkService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Shared between AGENCY_OWNER and AGENT for most actions - only /assign is genuinely
 * owner-only (per-method @PreAuthorize override, the blueprint's primary authorization
 * mechanism, §5.2), so a full audience-split controller pair isn't warranted here.
 */
@Slf4j
@RestController
@RequestMapping("/api/leads")
@RequiredArgsConstructor
@Tag(name = "Leads", description = "Lead pipeline, proposal builder, and visa tracker")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT')")
public class LeadController {

    private final LeadService leadService;
    private final ProposalLinkService proposalLinkService;

    @PostMapping
    @Operation(summary = "Create a lead (3-step wizard payload)")
    public ResponseEntity<LeadDetailResponse> createLead(@Valid @RequestBody LeadCreateRequest request) {
        return ResponseEntity.ok(leadService.createLead(request));
    }

    @GetMapping
    @Operation(summary = "List leads", description = "Agents see only their own leads; Owners see the whole agency.")
    public ResponseEntity<List<LeadResponse>> listLeads(
            @RequestParam(value = "status", required = false) LeadStatus status) {
        return ResponseEntity.ok(leadService.listLeads(status));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Lead detail")
    public ResponseEntity<LeadDetailResponse> getLead(@PathVariable String id) {
        return ResponseEntity.ok(leadService.getLead(id));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Update lead status", description = "lostReason is mandatory when status is LOST.")
    public ResponseEntity<LeadDetailResponse> updateStatus(@PathVariable String id,
                                                            @Valid @RequestBody LeadStatusUpdateRequest request) {
        return ResponseEntity.ok(leadService.updateStatus(id, request));
    }

    @PatchMapping("/{id}/follow-up")
    @Operation(summary = "Update the follow-up date")
    public ResponseEntity<LeadDetailResponse> updateFollowUp(@PathVariable String id,
                                                              @Valid @RequestBody LeadFollowUpUpdateRequest request) {
        return ResponseEntity.ok(leadService.updateFollowUp(id, request));
    }

    @PatchMapping("/{id}/assign")
    @PreAuthorize("hasRole('AGENCY_OWNER')")
    @Operation(summary = "Reassign the lead to a different agent", description = "Owner-only.")
    public ResponseEntity<LeadDetailResponse> assignAgent(@PathVariable String id,
                                                           @Valid @RequestBody LeadAssignRequest request) {
        return ResponseEntity.ok(leadService.assignAgent(id, request.getAgentId()));
    }

    @PostMapping("/{id}/notes")
    @Operation(summary = "Add a timestamped note / log a call on the lead")
    public ResponseEntity<LeadNoteResponse> addNote(@PathVariable String id,
                                                     @Valid @RequestBody LeadNoteCreateRequest request) {
        return ResponseEntity.ok(leadService.addNote(id, request));
    }

    @PostMapping("/{id}/proposal-items")
    @Operation(summary = "Add a line item to the proposal", description = "Margin % is always server-computed.")
    public ResponseEntity<ProposalItemResponse> addProposalItem(@PathVariable String id,
                                                                 @Valid @RequestBody ProposalItemCreateRequest request) {
        return ResponseEntity.ok(leadService.addProposalItem(id, request));
    }

    @DeleteMapping("/{id}/proposal-items/{itemId}")
    @Operation(summary = "Remove a proposal line item")
    public ResponseEntity<Void> removeProposalItem(@PathVariable String id, @PathVariable String itemId) {
        leadService.removeProposalItem(id, itemId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/visa-tracker")
    @Operation(summary = "Toggle one or more visa tracker steps")
    public ResponseEntity<LeadDetailResponse> updateVisaTracker(@PathVariable String id,
                                                                 @Valid @RequestBody VisaTrackerUpdateRequest request) {
        return ResponseEntity.ok(leadService.updateVisaTracker(id, request));
    }

    @PostMapping("/{id}/proposal-link")
    @Operation(summary = "Generate (or reuse) the public shareable proposal link")
    public ResponseEntity<ProposalLinkResponse> generateProposalLink(@PathVariable String id) {
        return ResponseEntity.ok(proposalLinkService.generateLink(id));
    }
}
