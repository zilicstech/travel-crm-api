package com.voyra.crm.controller;

import com.voyra.crm.dto.AuditLogResponse;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.enums.AuditEntityType;
import com.voyra.crm.service.AuditService;
import com.voyra.crm.util.PageRequestUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Append-only field-level change history. Read-only by design - no POST/PATCH/PUT/DELETE
 * exists anywhere in this file. Owner-only throughout: a per-entity-type agent-visibility rule
 * would be a growing conditional keyed by {@link AuditEntityType} where the default must always
 * be deny, so this keeps the smaller, safer surface until agent-visible history is asked for.
 */
@RestController
@RequestMapping("/api/audit-log")
@RequiredArgsConstructor
@Tag(name = "Audit Log", description = "Append-only field-level change history across the agency")
@PreAuthorize("hasRole('AGENCY_OWNER')")
public class AuditLogController {

    private final AuditService auditService;

    @GetMapping
    @Operation(summary = "Search the agency-wide audit trail", description = "Always paged - this table is unbounded.")
    public ResponseEntity<PagedResponse<AuditLogResponse>> search(
            @RequestParam(value = "entityType", required = false) AuditEntityType entityType,
            @RequestParam(value = "actorId", required = false) String actorId,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "page", required = false, defaultValue = "0") Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        return ResponseEntity.ok(auditService.search(entityType, actorId, from, to, PageRequestUtil.resolve(page, size)));
    }

    @GetMapping("/{entityType}/{entityId}")
    @Operation(summary = "One record's history", description = "Capped at 200 most recent entries.")
    public ResponseEntity<List<AuditLogResponse>> listForRecord(@PathVariable AuditEntityType entityType,
                                                                 @PathVariable String entityId) {
        return ResponseEntity.ok(auditService.listForRecord(entityType, entityId));
    }
}
