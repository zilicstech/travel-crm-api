package com.voyra.crm.controller;

import com.voyra.crm.dto.ActiveStatusUpdateRequest;
import com.voyra.crm.dto.AgentCreateRequest;
import com.voyra.crm.dto.AgentCreateResponse;
import com.voyra.crm.dto.AgentPerformanceResponse;
import com.voyra.crm.dto.AgentUpdateRequest;
import com.voyra.crm.dto.CredentialsResponse;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.service.AgentService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/agents")
@RequiredArgsConstructor
@Tag(name = "Owner - Agents", description = "AGENCY_OWNER management of their own Agents")
@PreAuthorize("hasRole('AGENCY_OWNER')")
public class OwnerAgentController {

    private final AgentService agentService;

    @PostMapping
    @Operation(summary = "Add a new Agent", description = "Generates login credentials for the agent.")
    public ResponseEntity<AgentCreateResponse> createAgent(@Valid @RequestBody AgentCreateRequest request) {
        return ResponseEntity.ok(agentService.createAgent(request));
    }

    @GetMapping
    @Operation(summary = "List agents (or accountants) in this agency, with performance KPIs",
            description = "Supply ?page= for a paged envelope; omit it for the full list as a plain array. "
                    + "?role= defaults to AGENT, so an assignee picker never lists accountants by accident.")
    public ResponseEntity<Object> listAgents(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestParam(value = "role", required = false) UserType role) {
        UserType effectiveRole = role != null ? role : UserType.AGENT;
        Pageable pageable = PageRequestUtil.resolve(page, size);
        return ResponseEntity.ok(pageable == null
                ? agentService.listAgents(effectiveRole)
                : agentService.listAgents(effectiveRole, pageable));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Agent detail with full performance breakdown")
    public ResponseEntity<AgentPerformanceResponse> getAgent(@PathVariable String id) {
        return ResponseEntity.ok(agentService.getAgent(id));
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('AGENT')")
    @Operation(summary = "The signed-in agent's own profile", description = "Self-service equivalent of GET /{id}, scoped to the caller's own record so an AGENT can resolve their own manageableServices without owner-only list access.")
    public ResponseEntity<AgentPerformanceResponse> getCurrentAgent() {
        return ResponseEntity.ok(agentService.getCurrentAgent());
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update an agent's profile")
    public ResponseEntity<AgentPerformanceResponse> updateAgent(@PathVariable String id,
                                                                 @Valid @RequestBody AgentUpdateRequest request) {
        return ResponseEntity.ok(agentService.updateAgent(id, request));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Activate or deactivate an agent")
    public ResponseEntity<AgentPerformanceResponse> updateStatus(@PathVariable String id,
                                                                  @Valid @RequestBody ActiveStatusUpdateRequest request) {
        return ResponseEntity.ok(agentService.updateStatus(id, request.getIsActive()));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Remove an agent", description = "Blocked (409) while the agent still has non-terminal leads assigned.")
    public ResponseEntity<Void> removeAgent(@PathVariable String id) {
        agentService.removeAgent(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/credentials")
    @Operation(summary = "Retrieve an agent's login credentials", description = "Every retrieval is logged.")
    public ResponseEntity<CredentialsResponse> getCredentials(@PathVariable String id) {
        return ResponseEntity.ok(agentService.getCredentials(id));
    }
}
