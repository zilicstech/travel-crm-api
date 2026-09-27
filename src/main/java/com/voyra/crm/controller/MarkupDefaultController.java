package com.voyra.crm.controller;

import com.voyra.crm.dto.MarkupDefaultResponse;
import com.voyra.crm.dto.MarkupDefaultUpdateRequest;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.service.MarkupDefaultService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The agency's default markup per service type - agents read it to pre-fill a quote line, only the Owner sets it. */
@Slf4j
@RestController
@RequestMapping("/api/agency/markup-defaults")
@RequiredArgsConstructor
@Tag(name = "Agency - Markup Defaults", description = "Default markup per service type")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT')")
public class MarkupDefaultController {

    private final MarkupDefaultService markupDefaultService;

    @GetMapping
    @Operation(summary = "List the markup default for every service type")
    public ResponseEntity<List<MarkupDefaultResponse>> list() {
        return ResponseEntity.ok(markupDefaultService.list());
    }

    @PutMapping("/{serviceType}")
    @PreAuthorize("hasRole('AGENCY_OWNER')")
    @Operation(summary = "Set the markup default for one service type")
    public ResponseEntity<MarkupDefaultResponse> upsert(@PathVariable ServiceType serviceType,
                                                          @Valid @RequestBody MarkupDefaultUpdateRequest request) {
        return ResponseEntity.ok(markupDefaultService.upsert(serviceType, request));
    }
}
