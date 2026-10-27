package com.voyra.crm.controller;

import com.voyra.crm.dto.PlatformSettingResponse;
import com.voyra.crm.dto.PlatformSettingUpdateRequest;
import com.voyra.crm.service.PlatformSettingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
}
