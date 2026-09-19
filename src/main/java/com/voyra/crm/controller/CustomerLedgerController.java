package com.voyra.crm.controller;

import com.voyra.crm.dto.ArAgeingRowResponse;
import com.voyra.crm.dto.ClientLedgerSummaryResponse;
import com.voyra.crm.dto.LedgerStatementResponse;
import com.voyra.crm.dto.OpeningBalanceRequest;
import com.voyra.crm.service.CustomerLedgerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** The customer subsidiary ledger - Owner/Accountant only, agents never see a client's financial position here. */
@Slf4j
@RestController
@RequestMapping("/api/accounts/ledger")
@RequiredArgsConstructor
@Tag(name = "Accounts - Ledger", description = "Customer statements, AR ageing, opening balances")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT')")
public class CustomerLedgerController {

    private final CustomerLedgerService customerLedgerService;

    @GetMapping("/clients/{clientId}")
    @Operation(summary = "Statement of account", description = "Every debit/credit row with a running INR balance. Omit from/to for the full history.")
    public ResponseEntity<LedgerStatementResponse> statement(
            @PathVariable String clientId,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(customerLedgerService.statement(clientId, from, to));
    }

    @GetMapping("/clients/{clientId}/summary")
    @Operation(summary = "Billed/received/outstanding/advance roll-up for one client")
    public ResponseEntity<ClientLedgerSummaryResponse> summary(@PathVariable String clientId) {
        return ResponseEntity.ok(customerLedgerService.summary(clientId));
    }

    @GetMapping("/outstanding")
    @Operation(summary = "AR ageing", description = "Every client with an unpaid tax invoice, bucketed 0-30/31-60/61-90/90+ days past due.")
    public ResponseEntity<List<ArAgeingRowResponse>> outstanding() {
        return ResponseEntity.ok(customerLedgerService.outstanding());
    }

    @PostMapping("/clients/{clientId}/opening-balance")
    @Operation(summary = "Post an opening balance", description = "Onboards a client with a pre-existing balance. Positive = client owes the agency.")
    public ResponseEntity<LedgerStatementResponse> openingBalance(
            @PathVariable String clientId, @Valid @RequestBody OpeningBalanceRequest request) {
        return ResponseEntity.ok(customerLedgerService.openingBalance(clientId, request));
    }
}
