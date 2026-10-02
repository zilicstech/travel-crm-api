package com.voyra.crm.controller;

import com.voyra.crm.dto.BankTransactionResponse;
import com.voyra.crm.dto.ConfirmBankMatchRequest;
import com.voyra.crm.dto.TierTwoCandidateResponse;
import com.voyra.crm.service.BankMatchingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Rule 4.3.1 - tier-2 candidates are surfaced here for a human to review and confirm; nothing on
 * the read side ever posts. {@link #confirmMatch} is the only endpoint that does, and it posts
 * through {@code PaymentReceiptService}/{@code SupplierPaymentService}'s real path (Rule 4.3.2).
 */
@RestController
@RequestMapping("/api/accounts/banking/transactions")
@RequiredArgsConstructor
@Tag(name = "Banking - Transactions", description = "Unmatched lines, tier-2 candidates, and match confirmation")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT')")
public class BankTransactionController {

    private final BankMatchingService bankMatchingService;

    @GetMapping("/unmatched")
    @Operation(summary = "Transactions with no match yet for one bank account")
    public ResponseEntity<List<BankTransactionResponse>> listUnmatched(@RequestParam("bankAccountId") String bankAccountId) {
        return ResponseEntity.ok(bankMatchingService.listUnmatched(bankAccountId));
    }

    @GetMapping("/tier2-candidates")
    @Operation(summary = "Probabilistic candidates for the side-by-side confirmation screen - never auto-applied")
    public ResponseEntity<List<TierTwoCandidateResponse>> tier2Candidates(@RequestParam("bankAccountId") String bankAccountId) {
        return ResponseEntity.ok(bankMatchingService.listTier2Candidates(bankAccountId));
    }

    @PostMapping("/{id}/confirm-match")
    @Operation(summary = "Confirm a tier-2 candidate or a manual pick - this is what actually posts the receipt/payment")
    public ResponseEntity<Void> confirmMatch(@PathVariable String id, @Valid @RequestBody ConfirmBankMatchRequest request) {
        bankMatchingService.confirmMatch(id, request.getSourceType(), request.getSourceId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/apply-rules")
    @Operation(summary = "Apply active bank_match_rule entries to whatever tier 1 and tier 2 left unmatched")
    public ResponseEntity<Integer> applyMatchRules(@RequestParam("bankAccountId") String bankAccountId) {
        return ResponseEntity.ok(bankMatchingService.applyMatchRules(bankAccountId));
    }
}
