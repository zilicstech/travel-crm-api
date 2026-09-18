package com.voyra.crm.controller;

import com.voyra.crm.dto.LeadCreateRequest;
import com.voyra.crm.dto.LeadDetailResponse;
import com.voyra.crm.dto.LeadDetailUpdateRequest;
import com.voyra.crm.dto.LeadEscalateRequest;
import com.voyra.crm.dto.LeadMemberAddRequest;
import com.voyra.crm.dto.LeadMemberResponse;
import com.voyra.crm.dto.LeadMemberUpdateRequest;
import com.voyra.crm.dto.LeadNoteCreateRequest;
import com.voyra.crm.dto.LeadNoteResponse;
import com.voyra.crm.dto.LeadProposalLockRequest;
import com.voyra.crm.dto.LeadResponse;
import com.voyra.crm.dto.LeadStatusUpdateRequest;
import com.voyra.crm.dto.ProposalItemBatchCreateRequest;
import com.voyra.crm.dto.ProposalItemCreateRequest;
import com.voyra.crm.dto.ProposalItemResponse;
import com.voyra.crm.dto.ProposalItemUpdateRequest;
import com.voyra.crm.dto.LeadTimelineResponse;
import com.voyra.crm.dto.ProposalLinkResponse;
import com.voyra.crm.enums.LeadStatus;
import com.voyra.crm.service.LeadService;
import com.voyra.crm.service.LeadTimelineService;
import com.voyra.crm.service.ProposalLinkService;
import com.voyra.crm.util.PageRequestUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
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
import org.springframework.web.bind.annotation.PutMapping;

import java.util.List;

/**
 * Shared between AGENCY_OWNER and AGENT for every lead-level action - a lead has no
 * owner-only reassignment any more (see LeadServiceController for the per-service
 * accept/assign split, which is where an owner-only action still lives on this model).
 */
@Slf4j
@RestController
@RequestMapping("/api/leads")
@RequiredArgsConstructor
@Tag(name = "Leads", description = "Lead pipeline, traveller manifest, proposal builder, and activity timeline")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT')")
public class LeadController {

    private final LeadService leadService;
    private final LeadTimelineService leadTimelineService;
    private final ProposalLinkService proposalLinkService;

    @PostMapping
    @Operation(summary = "Create a lead against an existing client",
            description = "Contact details come from the client's primary member. Use the client "
                    + "lookup endpoint first to find or create the client.")
    public ResponseEntity<LeadDetailResponse> createLead(@Valid @RequestBody LeadCreateRequest request) {
        return ResponseEntity.ok(leadService.createLead(request));
    }

    @GetMapping
    @Operation(summary = "List leads",
            description = "Agents see only leads they created; Owners see the whole agency. Supply ?page= "
                    + "for a paged envelope; omit it for the full list as a plain array.")
    public ResponseEntity<Object> listLeads(
            @RequestParam(value = "status", required = false) LeadStatus status,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        Pageable pageable = PageRequestUtil.resolve(page, size);
        return ResponseEntity.ok(pageable == null
                ? leadService.listLeads(status)
                : leadService.listLeads(status, pageable));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Lead detail")
    public ResponseEntity<LeadDetailResponse> getLead(@PathVariable String id) {
        return ResponseEntity.ok(leadService.getLead(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update trip-level details", description = "destination, budget and specialNotes - patch semantics.")
    public ResponseEntity<LeadDetailResponse> updateDetails(@PathVariable String id,
                                                             @Valid @RequestBody LeadDetailUpdateRequest request) {
        return ResponseEntity.ok(leadService.updateDetails(id, request));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Update lead status", description = "lostReason is mandatory when status is LOST.")
    public ResponseEntity<LeadDetailResponse> updateStatus(@PathVariable String id,
                                                            @Valid @RequestBody LeadStatusUpdateRequest request) {
        return ResponseEntity.ok(leadService.updateStatus(id, request));
    }

    @PatchMapping("/{id}/escalate")
    @Operation(summary = "Flag or clear a lead's escalation", description = "In-app surfacing only - "
            + "no notification is sent. reason is required when escalated is true.")
    public ResponseEntity<LeadDetailResponse> setEscalated(@PathVariable String id,
                                                            @Valid @RequestBody LeadEscalateRequest request) {
        return ResponseEntity.ok(leadService.setEscalated(id, request.getEscalated(), request.getReason()));
    }

    @PatchMapping("/{id}/proposal-lock")
    @Operation(summary = "Lock or unlock the whole proposal", description = "Locking freezes every "
            + "proposal-item write, agent-side included, and the customer's public selection endpoint. "
            + "Only an agent/owner can call this - the customer has no equivalent action.")
    public ResponseEntity<LeadDetailResponse> setProposalLocked(@PathVariable String id,
                                                                 @Valid @RequestBody LeadProposalLockRequest request) {
        return ResponseEntity.ok(leadService.setProposalLocked(id, request.getLocked()));
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

    @PostMapping("/{id}/proposal-items/batch")
    @Operation(summary = "Add several option lines to a service's proposal in one request",
            description = "All created lines share one option group (defaults to serviceId) as "
                    + "mutually-exclusive alternatives - none is selected until chosen via /select.")
    public ResponseEntity<List<ProposalItemResponse>> addProposalItemsBatch(
            @PathVariable String id, @Valid @RequestBody ProposalItemBatchCreateRequest request) {
        return ResponseEntity.ok(leadService.addProposalItemsBatch(id, request));
    }

    @PutMapping("/{id}/proposal-items/{itemId}")
    @Operation(summary = "Edit a proposal line item", description = "Margin % is always server-computed.")
    public ResponseEntity<ProposalItemResponse> updateProposalItem(@PathVariable String id, @PathVariable String itemId,
                                                                    @Valid @RequestBody ProposalItemUpdateRequest request) {
        return ResponseEntity.ok(leadService.updateProposalItem(id, itemId, request));
    }

    @PatchMapping("/{id}/proposal-items/{itemId}/select")
    @Operation(summary = "Make this option line the one that counts toward the total",
            description = "Every other line in the same option group is deselected in the same step.")
    public ResponseEntity<ProposalItemResponse> selectProposalItem(@PathVariable String id, @PathVariable String itemId) {
        return ResponseEntity.ok(leadService.selectProposalItem(id, itemId));
    }

    @DeleteMapping("/{id}/proposal-items/{itemId}")
    @Operation(summary = "Remove a proposal line item")
    public ResponseEntity<Void> removeProposalItem(@PathVariable String id, @PathVariable String itemId) {
        leadService.removeProposalItem(id, itemId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/members")
    @Operation(summary = "The lead's traveller manifest",
            description = "Includes DROPPED travellers, so the record of who was once on the trip and "
                    + "what documents were collected for them survives.")
    public ResponseEntity<List<LeadMemberResponse>> listMembers(@PathVariable String id) {
        return ResponseEntity.ok(leadService.listMembers(id));
    }

    @PostMapping("/{id}/members")
    @Operation(summary = "Add travellers to the lead",
            description = "Pick from the client's existing roster with memberIds, and/or create ad-hoc "
                    + "travellers inline with newMembers. Ad-hoc travellers join the client's roster so "
                    + "they are reusable on the next enquiry.")
    public ResponseEntity<List<LeadMemberResponse>> addMembers(@PathVariable String id,
                                                               @Valid @RequestBody LeadMemberAddRequest request) {
        return ResponseEntity.ok(leadService.addMembers(id, request));
    }

    @PatchMapping("/{id}/members/{memberId}")
    @Operation(summary = "Update one traveller's status or document checklist",
            description = "Removing a traveller means setting status to DROPPED with a reason - there is no "
                    + "delete, because the documents already collected for them have to stay on the record.")
    public ResponseEntity<LeadMemberResponse> updateMember(@PathVariable String id,
                                                           @PathVariable String memberId,
                                                           @Valid @RequestBody LeadMemberUpdateRequest request) {
        return ResponseEntity.ok(leadService.updateMember(id, memberId, request));
    }

    @GetMapping("/{id}/timeline")
    @Operation(summary = "The lead's activity stream, newest first",
            description = "Server-written audit trail of status changes, assignments and manifest edits. "
                    + "Read-only by design - agent-authored prose belongs in notes.")
    public ResponseEntity<List<LeadTimelineResponse>> getTimeline(@PathVariable String id) {
        leadService.getLead(id);
        return ResponseEntity.ok(leadTimelineService.listForLead(id));
    }

    @PostMapping("/{id}/proposal-link")
    @Operation(summary = "Generate (or reuse) the public shareable proposal link")
    public ResponseEntity<ProposalLinkResponse> generateProposalLink(@PathVariable String id) {
        return ResponseEntity.ok(proposalLinkService.generateLink(id));
    }
}
