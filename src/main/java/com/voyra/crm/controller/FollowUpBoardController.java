package com.voyra.crm.controller;

import com.voyra.crm.dto.FollowUpResponse;
import com.voyra.crm.enums.FollowUpStatus;
import com.voyra.crm.service.LeadFollowUpService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** An agent's own follow-up board, across every lead they touch. */
@Slf4j
@RestController
@RequestMapping("/api/follow-ups")
@RequiredArgsConstructor
@Tag(name = "Follow-up Board", description = "Cross-lead follow-up board")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT')")
public class FollowUpBoardController {

    private final LeadFollowUpService leadFollowUpService;

    @GetMapping
    @Operation(summary = "List follow-ups",
            description = "An agent always sees only their own; the owner sees everyone's unless agentId narrows it.")
    public ResponseEntity<List<FollowUpResponse>> board(
            @RequestParam(value = "agentId", required = false) String agentId,
            @RequestParam(value = "status", required = false) FollowUpStatus status,
            @RequestParam(value = "dueBefore", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueBefore) {
        return ResponseEntity.ok(leadFollowUpService.board(agentId, status, dueBefore));
    }
}
