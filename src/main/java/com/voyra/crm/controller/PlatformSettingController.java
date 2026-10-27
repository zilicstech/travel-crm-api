package com.voyra.crm.controller;

import com.voyra.crm.dto.PlatformSettingRequestResponse;
import com.voyra.crm.dto.PlatformSettingResponse;
import com.voyra.crm.dto.PlatformSettingUpdateRequest;
import com.voyra.crm.service.PlatformSettingRequestService;
import com.voyra.crm.service.PlatformSettingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/platform/settings")
@RequiredArgsConstructor
@Tag(name = "Platform - Settings", description = "SUPER_ADMIN platform-wide pricing settings")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class PlatformSettingController {

    private final PlatformSettingService service;
    private final PlatformSettingRequestService requests;

    @GetMapping
    @Operation(summary = "List platform settings, sorted by key")
    public ResponseEntity<List<PlatformSettingResponse>> list() {
        return ResponseEntity.ok(service.list());
    }

    @PutMapping("/{key}")
    @Operation(summary = "Update one platform setting")
    public ResponseEntity<PlatformSettingResponse> update(@PathVariable String key,
                                                          @Valid @RequestBody PlatformSettingUpdateRequest request) {
        return ResponseEntity.ok(service.set(key, request.getValue()));
    }

    @PostMapping("/{key}/requests")
    @Operation(summary = "Ask for a setting change a second platform admin must approve (tripjack_live_ready = true)")
    public ResponseEntity<PlatformSettingRequestResponse> request(@PathVariable String key,
                                                                  @Valid @RequestBody PlatformSettingUpdateRequest request) {
        return ResponseEntity.ok(requests.request(key, request.getValue()));
    }

    @GetMapping("/requests")
    @Operation(summary = "Setting changes waiting for a second platform admin")
    public ResponseEntity<List<PlatformSettingRequestResponse>> pending() {
        return ResponseEntity.ok(requests.pending());
    }

    @PostMapping("/requests/{id}/approve")
    @Operation(summary = "Approve another platform admin's setting change; it applies at once")
    public ResponseEntity<PlatformSettingRequestResponse> approve(@PathVariable String id) {
        return ResponseEntity.ok(requests.approve(id));
    }

    @PostMapping("/requests/{id}/withdraw")
    @Operation(summary = "Withdraw an open setting change request")
    public ResponseEntity<PlatformSettingRequestResponse> withdraw(@PathVariable String id) {
        return ResponseEntity.ok(requests.withdraw(id));
    }
}
