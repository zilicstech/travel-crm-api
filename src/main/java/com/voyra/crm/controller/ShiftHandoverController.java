package com.voyra.crm.controller;

import com.voyra.crm.dto.ShiftHandoverCreateRequest;
import com.voyra.crm.dto.ShiftHandoverResponse;
import com.voyra.crm.service.ShiftHandoverService;
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

/** A note per shift, not rostering/clock-in. Shared between AGENCY_OWNER and AGENT. */
@Slf4j
@RestController
@RequestMapping("/api/handovers")
@RequiredArgsConstructor
@Tag(name = "Shift Handovers", description = "A note left for the next shift")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT')")
public class ShiftHandoverController {

    private final ShiftHandoverService shiftHandoverService;

    @PostMapping
    @Operation(summary = "Leave a handover note")
    public ResponseEntity<ShiftHandoverResponse> create(@Valid @RequestBody ShiftHandoverCreateRequest request) {
        return ResponseEntity.ok(shiftHandoverService.create(request));
    }

    @GetMapping
    @Operation(summary = "List handovers", description = "Agents see ones addressed to them plus whole-team ones; owners see all.")
    public ResponseEntity<List<ShiftHandoverResponse>> list() {
        return ResponseEntity.ok(shiftHandoverService.list());
    }

    @PatchMapping("/{id}/acknowledge")
    @Operation(summary = "Acknowledge a handover", description = "Idempotent - acknowledging an already-acknowledged handover is a no-op.")
    public ResponseEntity<ShiftHandoverResponse> acknowledge(@PathVariable String id) {
        return ResponseEntity.ok(shiftHandoverService.acknowledge(id));
    }
}
