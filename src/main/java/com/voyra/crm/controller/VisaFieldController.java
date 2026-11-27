package com.voyra.crm.controller;

import com.voyra.crm.dto.VisaFieldOrderRequest;
import com.voyra.crm.dto.VisaFieldRequest;
import com.voyra.crm.dto.VisaFieldResponse;
import com.voyra.crm.service.VisaChecklistService;
import com.voyra.crm.service.VisaFieldService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Visa configuration: the fields an application asks for. Agents read them (Open Case, the sheet); only the owner edits. */
@RestController
@RequestMapping("/api/visa-fields")
@RequiredArgsConstructor
@Tag(name = "Visa fields", description = "Common and per-country fields of a visa application")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT')")
public class VisaFieldController {

    private final VisaFieldService service;
    private final VisaChecklistService checklistService;

    @GetMapping
    @Operation(summary = "Fields for one country", description = "Active common fields, then that country's. No country: common only.")
    public ResponseEntity<List<VisaFieldResponse>> forCountry(@RequestParam(value = "country", required = false) String country) {
        return ResponseEntity.ok(service.forCountry(country));
    }

    @GetMapping("/all")
    @Operation(summary = "Every active field", description = "Common and every country's, for Settings.")
    public ResponseEntity<List<VisaFieldResponse>> all() {
        return ResponseEntity.ok(service.all());
    }

    @GetMapping("/countries")
    @Operation(summary = "Countries that have fields")
    public ResponseEntity<List<String>> countries() {
        return ResponseEntity.ok(service.countries());
    }

    @GetMapping("/checklist")
    @Operation(summary = "Client checklist PDF for one country",
            description = "The country's common and own fields as a checklist to send the client; skip leaves fields off.")
    public ResponseEntity<Resource> checklist(@RequestParam("country") String country,
                                              @RequestParam(value = "skip", required = false) List<String> skip) {
        VisaChecklistService.ChecklistPdf pdf = checklistService.pdf(country, skip);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(pdf.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(new ByteArrayResource(pdf.bytes()));
    }

    @PostMapping
    @PreAuthorize("hasRole('AGENCY_OWNER')")
    @Operation(summary = "Add a field", description = "Owner-only. A label that matches a removed field of the same list restores it.")
    public ResponseEntity<VisaFieldResponse> create(@Valid @RequestBody VisaFieldRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    @PutMapping("/order")
    @PreAuthorize("hasRole('AGENCY_OWNER')")
    @Operation(summary = "Reorder one list", description = "Owner-only.")
    public ResponseEntity<List<VisaFieldResponse>> reorder(@Valid @RequestBody VisaFieldOrderRequest request) {
        return ResponseEntity.ok(service.reorder(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('AGENCY_OWNER')")
    @Operation(summary = "Edit a field", description = "Owner-only. Type, scope and country cannot change.")
    public ResponseEntity<VisaFieldResponse> update(@PathVariable String id, @Valid @RequestBody VisaFieldRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('AGENCY_OWNER')")
    @Operation(summary = "Remove a field", description = "Owner-only. Old applications keep its values.")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
