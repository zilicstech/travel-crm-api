package com.voyra.crm.controller;

import com.voyra.crm.dto.FollowUpCreateRequest;
import com.voyra.crm.dto.FollowUpResponse;
import com.voyra.crm.service.LeadFollowUpService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/leads/{leadId}/follow-ups")
@RequiredArgsConstructor
@Tag(name = "Lead Follow-ups", description = "Promises to chase something on a lead, scoped to a service type or trip-wide")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT')")
public class LeadFollowUpController {

    private final LeadFollowUpService leadFollowUpService;

    @PostMapping
    @Operation(summary = "Add a follow-up")
    public ResponseEntity<FollowUpResponse> addFollowUp(@PathVariable String leadId,
                                                         @Valid @RequestBody FollowUpCreateRequest request) {
        return ResponseEntity.ok(leadFollowUpService.addFollowUp(leadId, request));
    }

    @GetMapping
    @Operation(summary = "This lead's follow-ups")
    public ResponseEntity<List<FollowUpResponse>> listFollowUps(@PathVariable String leadId) {
        return ResponseEntity.ok(leadFollowUpService.listForLead(leadId));
    }

    @PatchMapping("/{followUpId}/complete")
    @Operation(summary = "Mark a follow-up done", description = "Completed, never deleted - what was promised and whether it was kept is the record.")
    public ResponseEntity<FollowUpResponse> completeFollowUp(@PathVariable String leadId,
                                                              @PathVariable String followUpId) {
        return ResponseEntity.ok(leadFollowUpService.completeFollowUp(leadId, followUpId));
    }

    @DeleteMapping("/{followUpId}")
    @Operation(summary = "Remove a follow-up", description = "For a genuine entry mistake, not for completing a promise.")
    public ResponseEntity<Void> deleteFollowUp(@PathVariable String leadId, @PathVariable String followUpId) {
        leadFollowUpService.deleteFollowUp(leadId, followUpId);
        return ResponseEntity.noContent().build();
    }
}
