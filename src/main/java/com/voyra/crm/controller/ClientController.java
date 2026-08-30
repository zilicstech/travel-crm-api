package com.voyra.crm.controller;

import com.voyra.crm.dto.ActiveStatusUpdateRequest;
import com.voyra.crm.dto.ClientCreateRequest;
import com.voyra.crm.dto.ClientDetailResponse;
import com.voyra.crm.dto.ClientLookupResponse;
import com.voyra.crm.dto.ClientUpdateRequest;
import com.voyra.crm.enums.ClientType;
import com.voyra.crm.service.ClientService;
import com.voyra.crm.util.PageRequestUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Clients: the commercial entities the agency deals with, B2C or B2B.
 *
 * <p>Shared between AGENCY_OWNER and AGENT. There is no per-method override here - which rows
 * an agent may touch is an ownership question the service answers, not something a role
 * expression can express (blueprint §5.2).
 */
@Slf4j
@RestController
@RequestMapping("/api/clients")
@RequiredArgsConstructor
@Tag(name = "Clients", description = "B2C and B2B client accounts and their traveller rosters")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT')")
public class ClientController {

    private final ClientService clientService;

    @PostMapping
    @Operation(summary = "Create a client and its primary member",
            description = "The primary member is created in the same call - a client with nobody to "
                    + "contact has no source for the name snapshot every downstream record carries.")
    public ResponseEntity<ClientDetailResponse> createClient(@Valid @RequestBody ClientCreateRequest request) {
        return ResponseEntity.ok(clientService.createClient(request));
    }

    @GetMapping
    @Operation(summary = "List clients",
            description = "Agents see only their own clients; Owners see the whole agency. Supply ?page= "
                    + "for a paged envelope; omit it for the full list as a plain array.")
    public ResponseEntity<Object> listClients(
            @RequestParam(value = "type", required = false) ClientType type,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        Pageable pageable = PageRequestUtil.resolve(page, size);
        return ResponseEntity.ok(pageable == null
                ? clientService.listClients(type)
                : clientService.listClients(type, pageable));
    }

    @GetMapping("/lookup")
    @Operation(summary = "Find an existing client by identifier",
            description = "Duplicate check for the Add Lead wizard. Searches the whole agency, not just "
                    + "the caller's own clients, so a walk-in already known to a colleague is found "
                    + "rather than created twice.")
    public ResponseEntity<ClientLookupResponse> lookup(@RequestParam("identifier") String identifier) {
        return ResponseEntity.ok(clientService.lookupByIdentifier(identifier));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Client detail with its member roster")
    public ResponseEntity<ClientDetailResponse> getClient(@PathVariable String id) {
        return ResponseEntity.ok(clientService.getClient(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a client",
            description = "Renaming re-syncs the client name snapshot on every lead, booking, invoice "
                    + "and visa case in the same transaction.")
    public ResponseEntity<ClientDetailResponse> updateClient(@PathVariable String id,
                                                             @Valid @RequestBody ClientUpdateRequest request) {
        return ResponseEntity.ok(clientService.updateClient(id, request));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Activate or deactivate a client",
            description = "Deactivation rather than deletion, so bookings and invoices keep resolving. "
                    + "Rejected with 409 while the client still has open leads.")
    public ResponseEntity<ClientDetailResponse> updateStatus(@PathVariable String id,
                                                             @Valid @RequestBody ActiveStatusUpdateRequest request) {
        return ResponseEntity.ok(clientService.updateStatus(id, request.getIsActive()));
    }
}
