package com.voyra.crm.controller;

import com.voyra.crm.dto.VisaChecklistUpdateRequest;
import com.voyra.crm.dto.VisaCreateRequest;
import com.voyra.crm.dto.VisaDashboardSummaryResponse;
import com.voyra.crm.dto.VisaFileResponse;
import com.voyra.crm.dto.VisaResponse;
import com.voyra.crm.dto.VisaValuesUpdateRequest;
import com.voyra.crm.service.VisaService;
import com.voyra.crm.util.PageRequestUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.RestController;


/**
 * Standalone Visa case management (the Owner's dedicated Visa dashboard) - distinct from the
 * lightweight 5-step visa tracker embedded on a Lead (see LeadController's /visa-tracker).
 */
@Slf4j
@RestController
@RequestMapping("/api/visas")
@RequiredArgsConstructor
@Tag(name = "Visas", description = "Standalone visa case tracking and pipeline dashboard")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT')")
public class VisaController {

    private final VisaService visaService;
    private final com.voyra.crm.service.VisaExportService visaExportService;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Open a new visa case (no files)", description = "Kept for old clients; File fields need the multipart form.")
    public ResponseEntity<VisaResponse> createVisa(@Valid @RequestBody VisaCreateRequest request) {
        return ResponseEntity.ok(visaService.createVisa(request));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Open a new visa case with its files",
            description = "Part `request` is the JSON body; every other part is the file of the File field it is named after.")
    public ResponseEntity<VisaResponse> createVisaWithFiles(@Valid @RequestPart("request") VisaCreateRequest request,
                                                            @RequestParam Map<String, MultipartFile> files) {
        Map<String, MultipartFile> parts = new LinkedHashMap<>(files);
        parts.remove("request");
        return ResponseEntity.ok(visaService.createVisa(request, parts));
    }

    @PatchMapping("/{id}/values")
    @Operation(summary = "Edit fields and facts in place",
            description = "Partial: null leaves a value, \"\" clears it. A stale version is 409 VISA_CHANGED with the current case.")
    public ResponseEntity<VisaResponse> updateValues(@PathVariable String id, @Valid @RequestBody VisaValuesUpdateRequest request) {
        return ResponseEntity.ok(visaService.updateValues(id, request));
    }

    @PutMapping(value = "/{id}/files/{fieldId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload or replace a File field's file")
    public ResponseEntity<VisaFileResponse> uploadFile(@PathVariable String id, @PathVariable String fieldId,
                                                       @RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(visaService.uploadFile(id, fieldId, file));
    }

    @GetMapping("/{id}/files/{fieldId}")
    @Operation(summary = "Download a File field's file")
    public ResponseEntity<Resource> file(@PathVariable String id, @PathVariable String fieldId) {
        VisaService.StoredVisaFile f = visaService.file(id, fieldId);
        return ResponseEntity.ok()
                .contentType(f.contentType() != null ? MediaType.parseMediaType(f.contentType()) : MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(f.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(f.resource());
    }

    @DeleteMapping("/{id}/files/{fieldId}")
    @Operation(summary = "Remove an optional File field's file", description = "A mandatory field's file can only be replaced.")
    public ResponseEntity<Void> deleteFile(@PathVariable String id, @PathVariable String fieldId) {
        visaService.deleteFile(id, fieldId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    @Operation(summary = "List visa cases (the sheet)",
            description = "Agents see only their own cases; Owners see the whole agency. Supply ?page= for a paged envelope "
                    + "with search (q: traveller, client, passport number, case number), filters (country, status, agentId, "
                    + "openedFrom/openedTo, f.<fieldId>=value, f.<dateFieldId>.from/.to, f.<fileFieldId>=has|missing) and "
                    + "sort (caseNo, createdDate, clientName, memberName, status, passportExpiry, country, agentName, visaType, "
                    + "sellingPrice or field.<fieldId>, then ,asc or ,desc); omit page for the full list as a plain array.")
    public ResponseEntity<Object> listVisas(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "country", required = false) String country,
            @RequestParam(value = "status", required = false) com.voyra.crm.enums.VisaStatus status,
            @RequestParam(value = "agentId", required = false) String agentId,
            @RequestParam(value = "openedFrom", required = false) java.time.LocalDate openedFrom,
            @RequestParam(value = "openedTo", required = false) java.time.LocalDate openedTo,
            @RequestParam Map<String, String> params) {
        Pageable pageable = PageRequestUtil.resolve(page, size);
        if (pageable == null) {
            return ResponseEntity.ok(visaService.listVisas());
        }
        return ResponseEntity.ok(visaService.searchVisas(
                new com.voyra.crm.models.VisaListFilter(q, country, status, agentId, openedFrom, openedTo, fieldFilters(params)), sort, pageable));
    }

    @GetMapping("/export.csv")
    @Operation(summary = "Export the sheet as CSV", description = "Same filters, order and scope as the list; at most 5000 rows "
            + "(X-Truncated: true when there were more). Audited.")
    public ResponseEntity<String> exportCsv(
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "country", required = false) String country,
            @RequestParam(value = "status", required = false) com.voyra.crm.enums.VisaStatus status,
            @RequestParam(value = "agentId", required = false) String agentId,
            @RequestParam(value = "openedFrom", required = false) java.time.LocalDate openedFrom,
            @RequestParam(value = "openedTo", required = false) java.time.LocalDate openedTo,
            @RequestParam Map<String, String> params) {
        com.voyra.crm.service.VisaExportService.Export export = visaExportService.export(
                new com.voyra.crm.models.VisaListFilter(q, country, status, agentId, openedFrom, openedTo, fieldFilters(params)), sort);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"visa-applications.csv\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Truncated", String.valueOf(export.truncated()))
                .body(export.csv());
    }

    /** f.<id>=v, f.<id>.from=d ... -> <id>=v, <id>.from=d. */
    private static Map<String, String> fieldFilters(Map<String, String> params) {
        Map<String, String> out = new LinkedHashMap<>();
        params.forEach((k, v) -> {
            if (k.startsWith("f.")) out.put(k.substring(2), v);
        });
        return out;
    }

    @GetMapping("/{id}")
    @Operation(summary = "Visa case detail")
    public ResponseEntity<VisaResponse> getVisa(@PathVariable String id) {
        return ResponseEntity.ok(visaService.getVisa(id));
    }

    @PatchMapping("/{id}/checklist")
    @Operation(summary = "Update the visa checklist", description = "A record of the steps; the case's overall status is set separately (PATCH /values, status).")
    public ResponseEntity<VisaResponse> updateChecklist(@PathVariable String id,
                                                         @Valid @RequestBody VisaChecklistUpdateRequest request) {
        return ResponseEntity.ok(visaService.updateChecklist(id, request));
    }

    @GetMapping("/dashboard-summary")
    @Operation(summary = "Visa pipeline KPI summary")
    public ResponseEntity<VisaDashboardSummaryResponse> getDashboardSummary() {
        return ResponseEntity.ok(visaService.getDashboardSummary());
    }
}
