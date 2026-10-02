package com.voyra.crm.controller;

import com.voyra.crm.dto.ControlAccountReconciliationRowResponse;
import com.voyra.crm.service.ControlAccountReconciliationService;
import com.voyra.crm.service.OpeningBalanceBackfillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Rule 1.7.2 - what makes it safe to add a general ledger to a system already holding live
 * money. Every control account's GL balance against its subsidiary ledger total; the variance
 * must read zero across a full financial period before any GL-derived statement is trusted
 * (Rule 10.1 of the implementation plan).
 */
@RestController
@RequestMapping("/api/accounts/reconciliation")
@RequiredArgsConstructor
@Tag(name = "Accounts - Reconciliation", description = "Control-account GL balance vs subsidiary ledger total")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT')")
public class ControlAccountReconciliationController {

    private final ControlAccountReconciliationService controlAccountReconciliationService;
    private final OpeningBalanceBackfillService openingBalanceBackfillService;

    @GetMapping("/control-accounts")
    @Operation(summary = "Per control account: GL balance, subsidiary ledger total, and the variance")
    public ResponseEntity<List<ControlAccountReconciliationRowResponse>> controlAccounts() {
        return ResponseEntity.ok(controlAccountReconciliationService.reconcile());
    }

    /**
     * Posting-rule-table row 17, for this tenant only. Owner-only (not Accountant) - this posts
     * real journal entries closing whatever variance exists right now against 3100, a one-way
     * action a human should deliberately choose, not something a daily user triggers by accident.
     * Idempotent: an account already carrying an OPENING_BALANCE_POSTED entry, or already at zero
     * variance, is skipped - safe to call again after new activity reopens a gap.
     */
    @PostMapping("/opening-balance-backfill")
    @PreAuthorize("hasRole('AGENCY_OWNER')")
    @Operation(summary = "Close this tenant's current control-account variance against Opening Balance Equity (3100)",
            description = "One-time GL cutover catch-up (Rule 17 / Gate 2.1) - posts an opening-balance journal "
                    + "for every control account whose GL balance does not yet match its subsidiary ledger total. "
                    + "Safe to call repeatedly: already-posted or already-zero accounts are skipped.")
    public ResponseEntity<Map<String, Integer>> openingBalanceBackfill() {
        int posted = openingBalanceBackfillService.backfillCurrentTenant();
        return ResponseEntity.ok(Map.of("accountsPosted", posted));
    }
}
