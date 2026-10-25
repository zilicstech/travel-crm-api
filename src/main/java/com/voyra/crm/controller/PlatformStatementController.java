package com.voyra.crm.controller;

import com.voyra.crm.dto.StatementImportResponse;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.service.TripjackStatementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/platform/statements")
@RequiredArgsConstructor
@Tag(name = "Platform - Supplier statements", description = "SUPER_ADMIN import of Tripjack statements to confirm Voyra's earnings")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class PlatformStatementController {

    private final TripjackStatementService service;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Import a Tripjack statement CSV",
            description = "Columns booking_id, pnr, type (SALE|REFUND), amount, commission, date - mapped by header name. "
                    + "CSV only, up to 5 MB. Only bookings of ?environment= (default PROD; UAT or MOCK to test) match. The same file again is 409 \"Already imported\"; a line already applied is listed as DUPLICATE. Audited.")
    public ResponseEntity<StatementImportResponse> importStatement(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "environment", defaultValue = "PROD") SupplierEnvironment environment) {
        CustomUserPrincipal actor = SecurityContextUtil.getCurrentUserOrThrow();
        return ResponseEntity.ok(service.importStatement(file, environment, actor.userId(), actor.username()));
    }
}
