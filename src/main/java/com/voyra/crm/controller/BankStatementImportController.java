package com.voyra.crm.controller;

import com.voyra.crm.dto.BankStatementImportResponse;
import com.voyra.crm.service.BankStatementImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Rule 4.2 - upload and parse-then-persist. Not wrapped in a transaction at the controller level; the service owns the three-phase split. */
@Slf4j
@RestController
@RequestMapping("/api/accounts/banking/imports")
@RequiredArgsConstructor
@Tag(name = "Banking - Statement Imports", description = "Upload and parse a bank statement, tier-1 matching runs immediately")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT')")
public class BankStatementImportController {

    private final BankStatementImportService bankStatementImportService;

    @PostMapping(consumes = "multipart/form-data")
    @Operation(summary = "Import a statement file",
            description = "Stores the file, parses it per the chosen profile, persists every non-duplicate row, and runs tier-1 exact matching in the same pass.")
    public ResponseEntity<BankStatementImportResponse> importStatement(
            @RequestParam("bankAccountId") String bankAccountId,
            @RequestParam("profileId") String profileId,
            @RequestPart("file") MultipartFile file) {
        BankStatementImportService.BankImportOutcome outcome = bankStatementImportService.importStatement(bankAccountId, profileId, file);
        return ResponseEntity.ok(BankStatementImportResponse.builder()
                .id(outcome.importId())
                .bankAccountId(bankAccountId)
                .fileName(file.getOriginalFilename())
                .rowCount(outcome.rowsSaved())
                .duplicateCount(outcome.duplicatesSkipped())
                .tier1Matched(outcome.tier1Matched())
                .status(com.voyra.crm.enums.BankStatementImportStatus.COMPLETED)
                .parseErrors(outcome.parseErrors())
                .build());
    }
}
