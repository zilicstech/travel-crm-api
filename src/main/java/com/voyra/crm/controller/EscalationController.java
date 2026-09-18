package com.voyra.crm.controller;

import com.voyra.crm.dto.EscalationResponse;
import com.voyra.crm.service.EscalationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The agency's "needs attention" feed - overdue follow-ups, past-deadline bookings, overdue
 * invoices and manually-escalated leads, unioned and severity-ranked. Read-only, in-app only.
 */
@RestController
@RequestMapping("/api/escalations")
@RequiredArgsConstructor
@Tag(name = "Escalations", description = "Agency-wide (or agent-scoped) needs-attention feed")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT')")
public class EscalationController {

    private final EscalationService escalationService;

    @GetMapping
    @Operation(summary = "List everything that needs attention right now",
            description = "Agents see only their own; owners see the whole agency.")
    public ResponseEntity<List<EscalationResponse>> list() {
        return ResponseEntity.ok(escalationService.list());
    }
}
