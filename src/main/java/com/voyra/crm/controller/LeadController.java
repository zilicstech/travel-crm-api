package com.voyra.crm.controller;

import com.voyra.crm.dto.LeadAssignRequest;
import com.voyra.crm.dto.LeadCreateRequest;
import com.voyra.crm.dto.LeadDetailResponse;
import com.voyra.crm.dto.LeadFollowUpUpdateRequest;
import com.voyra.crm.dto.LeadMemberAddRequest;
import com.voyra.crm.dto.LeadMemberResponse;
import com.voyra.crm.dto.LeadMemberUpdateRequest;
import com.voyra.crm.dto.LeadNoteCreateRequest;
import com.voyra.crm.dto.LeadNoteResponse;
import com.voyra.crm.dto.LeadResponse;
import com.voyra.crm.dto.LeadStatusUpdateRequest;
import com.voyra.crm.dto.ProposalItemCreateRequest;
import com.voyra.crm.dto.ProposalItemResponse;
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
            description = "Agents see only their own assigned leads; Owners see the whole agency. Supply ?page= "
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
