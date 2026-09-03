package com.voyra.crm.controller;

import com.voyra.crm.dto.ServiceResponse;
import com.voyra.crm.enums.ServiceStatus;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.service.ServiceInstanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The cross-lead Services board - every service instance across every lead, scoped to the
 * caller's manageable service types (owner sees all). This is the only route into the feature
 * for an agent who handles a service on someone else's lead; the Leads list is organised around
 * lead ownership, so without this an agent who only manages Visa would have nothing to click.
 */
@Slf4j
@RestController
@RequestMapping("/api/services")
@RequiredArgsConstructor
@Tag(name = "Service Board", description = "Cross-lead view of every service instance")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT')")
public class ServiceBoardController {

    private final ServiceInstanceService serviceInstanceService;

    @GetMapping
    @Operation(summary = "List services across every lead",
            description = "An agent sees only the types in their own manageableServices; the owner sees everything.")
    public ResponseEntity<List<ServiceResponse>> board(
            @RequestParam(value = "type", required = false) ServiceType type,
            @RequestParam(value = "status", required = false) ServiceStatus status) {
        return ResponseEntity.ok(serviceInstanceService.board(type, status));
    }
}
