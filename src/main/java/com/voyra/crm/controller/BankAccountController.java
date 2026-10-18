package com.voyra.crm.controller;

import com.voyra.crm.dto.BankAccountRequest;
import com.voyra.crm.dto.BankAccountResponse;
import com.voyra.crm.service.BankAccountService;
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

/** Real bank accounts the agency holds. Owner/Accountant only, matching the rest of the accounts surface. */
@RestController
@RequestMapping("/api/accounts/banking/accounts")
@RequiredArgsConstructor
@Tag(name = "Banking - Accounts", description = "Bank accounts the agency holds, each tied to a GL account")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT')")
public class BankAccountController {

    private final BankAccountService bankAccountService;

    @PostMapping
    @Operation(summary = "Register a bank account")
    public ResponseEntity<BankAccountResponse> create(@Valid @RequestBody BankAccountRequest request) {
        return ResponseEntity.ok(bankAccountService.create(request));
    }

    @GetMapping
    @Operation(summary = "List bank accounts")
    public ResponseEntity<List<BankAccountResponse>> list() {
        return ResponseEntity.ok(bankAccountService.list());
    }

    @PutMapping("/{id}")
    @Operation(summary = "Edit a bank account")
    public ResponseEntity<BankAccountResponse> update(@PathVariable String id, @Valid @RequestBody BankAccountRequest request) {
        return ResponseEntity.ok(bankAccountService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a bank account", description = "Soft delete; refused once the account has posted lines or payments.")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        bankAccountService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
