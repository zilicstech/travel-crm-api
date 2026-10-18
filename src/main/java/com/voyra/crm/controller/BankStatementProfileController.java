package com.voyra.crm.controller;

import com.voyra.crm.dto.BankStatementProfileRequest;
import com.voyra.crm.dto.BankStatementProfileResponse;
import com.voyra.crm.service.BankStatementProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Rule 4.1.1 - a profile is mandatory before any statement of a given bank can be imported. */
@RestController
@RequestMapping("/api/accounts/banking/statement-profiles")
@RequiredArgsConstructor
@Tag(name = "Banking - Statement Profiles", description = "Per-bank column mapping and amount convention")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT')")
public class BankStatementProfileController {

    private final BankStatementProfileService bankStatementProfileService;

    @PostMapping
    @Operation(summary = "Create a statement profile for a bank")
    public ResponseEntity<BankStatementProfileResponse> create(@Valid @RequestBody BankStatementProfileRequest request) {
        return ResponseEntity.ok(bankStatementProfileService.create(request));
    }

    @GetMapping
    @Operation(summary = "List statement profiles")
    public ResponseEntity<List<BankStatementProfileResponse>> list() {
        return ResponseEntity.ok(bankStatementProfileService.list());
    }

    @PutMapping("/{id}")
    @Operation(summary = "Edit a statement profile")
    public ResponseEntity<BankStatementProfileResponse> update(@PathVariable String id, @Valid @RequestBody BankStatementProfileRequest request) {
        return ResponseEntity.ok(bankStatementProfileService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a statement profile", description = "Soft delete; statements already imported stay imported.")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        bankStatementProfileService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
