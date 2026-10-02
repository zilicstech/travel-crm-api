package com.voyra.crm.controller;

import com.voyra.crm.dto.BankMatchRuleRequest;
import com.voyra.crm.dto.BankMatchRuleResponse;
import com.voyra.crm.service.BankMatchRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Rule 4.3.3 - categorisation rules for recurring, non-booking bank lines (wire fees, interest). */
@RestController
@RequestMapping("/api/accounts/banking/match-rules")
@RequiredArgsConstructor
@Tag(name = "Banking - Match Rules", description = "Pattern-based categorisation for transactions tier 1 and tier 2 leave unmatched")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT')")
public class BankMatchRuleController {

    private final BankMatchRuleService bankMatchRuleService;

    @PostMapping
    @Operation(summary = "Create a categorisation rule")
    public ResponseEntity<BankMatchRuleResponse> create(@Valid @RequestBody BankMatchRuleRequest request) {
        return ResponseEntity.ok(bankMatchRuleService.create(request));
    }

    @GetMapping
    @Operation(summary = "List active rules, in priority order")
    public ResponseEntity<List<BankMatchRuleResponse>> list() {
        return ResponseEntity.ok(bankMatchRuleService.list());
    }
}
