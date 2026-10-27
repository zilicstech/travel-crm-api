package com.voyra.crm.controller;

import com.voyra.crm.dto.PlatformAdminCreateRequest;
import com.voyra.crm.dto.PlatformAdminResponse;
import com.voyra.crm.service.PlatformAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/platform/admins")
@RequiredArgsConstructor
@Tag(name = "Platform - Admins", description = "SUPER_ADMIN logins: list, add, deactivate, reactivate")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class PlatformAdminController {

    private final PlatformAdminService service;

    @GetMapping
    @Operation(summary = "Every platform admin, by name")
    public ResponseEntity<List<PlatformAdminResponse>> list() {
        return ResponseEntity.ok(service.list());
    }

    @PostMapping
    @Operation(summary = "Add a platform admin", description = "The response carries the generated password once. Audited.")
    public ResponseEntity<PlatformAdminResponse> create(@Valid @RequestBody PlatformAdminCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PostMapping("/{id}/deactivate")
    @Operation(summary = "Deactivate a platform admin's login", description = "Audited. Refused for the last active admin.")
    @ApiResponse(responseCode = "409", description = "This is the only active platform admin")
    public ResponseEntity<PlatformAdminResponse> deactivate(@PathVariable String id) {
        return ResponseEntity.ok(service.deactivate(id));
    }

    @PostMapping("/{id}/reactivate")
    @Operation(summary = "Turn a platform admin's login back on", description = "Audited.")
    public ResponseEntity<PlatformAdminResponse> reactivate(@PathVariable String id) {
        return ResponseEntity.ok(service.reactivate(id));
    }
}
