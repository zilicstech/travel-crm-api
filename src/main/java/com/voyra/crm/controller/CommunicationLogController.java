package com.voyra.crm.controller;

import com.voyra.crm.dto.CommunicationLogCreateRequest;
import com.voyra.crm.dto.CommunicationLogResponse;
import com.voyra.crm.service.CommunicationLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** Manually-logged calls, WhatsApp threads, emails and meetings for a client. */
@Slf4j
@RestController
@RequestMapping("/api/clients/{clientId}/communications")
@RequiredArgsConstructor
@Tag(name = "Communication Log", description = "Manually-logged calls, WhatsApp threads, emails and meetings")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT')")
public class CommunicationLogController {

    private final CommunicationLogService communicationLogService;

    @PostMapping
    @Operation(summary = "Log a communication")
    public ResponseEntity<CommunicationLogResponse> create(@PathVariable String clientId,
                                                            @Valid @RequestBody CommunicationLogCreateRequest request) {
        return ResponseEntity.ok(communicationLogService.create(clientId, request));
    }

    @GetMapping
    @Operation(summary = "This client's communication history")
    public ResponseEntity<List<CommunicationLogResponse>> list(@PathVariable String clientId) {
        return ResponseEntity.ok(communicationLogService.listForClient(clientId));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Remove a logged communication")
    public ResponseEntity<Void> delete(@PathVariable String clientId, @PathVariable String id) {
        communicationLogService.delete(clientId, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/{id}/file", consumes = "multipart/form-data")
    @Operation(summary = "Attach or replace a file (screenshot, email export)")
    public ResponseEntity<CommunicationLogResponse> attachFile(@PathVariable String clientId,
                                                                @PathVariable String id,
                                                                @RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(communicationLogService.attachFile(clientId, id, file));
    }

    @GetMapping("/{id}/file")
    @Operation(summary = "Download or view the attached file")
    public ResponseEntity<Resource> downloadFile(@PathVariable String clientId, @PathVariable String id) {
        CommunicationLogService.CommunicationFileContent content = communicationLogService.downloadFile(clientId, id);
        MediaType mediaType = content.contentType() != null
                ? MediaType.parseMediaType(content.contentType())
                : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + content.filename() + "\"")
                .body(content.resource());
    }
}
