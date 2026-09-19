package com.voyra.crm.controller;

import com.voyra.crm.dto.CreditNoteCancelRequest;
import com.voyra.crm.dto.CreditNoteRefundRequest;
import com.voyra.crm.dto.CreditNoteRequest;
import com.voyra.crm.dto.CreditNoteResponse;
import com.voyra.crm.service.CreditNoteService;
import com.voyra.crm.util.PageRequestUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Credit notes - Owner/Accountant only, unlike invoices and receipts an Agent never sees these.
 */
@Slf4j
@RestController
@RequestMapping("/api/accounts/credit-notes")
@RequiredArgsConstructor
@Tag(name = "Accounts - Credit Notes", description = "Whole-or-part reversals against an issued tax invoice, and their cash refunds")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT')")
public class CreditNoteController {

    private final CreditNoteService creditNoteService;

    @PostMapping
    @Operation(summary = "Draft a credit note", description = "Computes the tax reversal pro-rata at the invoice's own persisted rates. Consumes no number until issued.")
    public ResponseEntity<CreditNoteResponse> create(@Valid @RequestBody CreditNoteRequest request) {
        return ResponseEntity.ok(creditNoteService.create(request));
    }

    @GetMapping
    @Operation(summary = "List credit notes", description = "Supply ?page= for a paged envelope; omit it for the full list as a plain array.")
    public ResponseEntity<Object> list(
            @RequestParam(value = "invoiceId", required = false) String invoiceId,
            @RequestParam(value = "clientId", required = false) String clientId,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        Pageable pageable = PageRequestUtil.resolve(page, size);
        return ResponseEntity.ok(creditNoteService.list(invoiceId, clientId, pageable));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one credit note")
    public ResponseEntity<CreditNoteResponse> get(@PathVariable String id) {
        return ResponseEntity.ok(creditNoteService.get(id));
    }

    @PostMapping("/{id}/issue")
    @Operation(summary = "Issue a credit note", description = "Allocates a CN/... number, posts the ledger credit, and reduces the invoice's balance due. Immutable from here.")
    public ResponseEntity<CreditNoteResponse> issue(@PathVariable String id) {
        return ResponseEntity.ok(creditNoteService.issue(id));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a credit note", description = "A draft is simply withdrawn. An issued one may only be cancelled if nothing has been refunded against it yet.")
    public ResponseEntity<CreditNoteResponse> cancel(@PathVariable String id, @Valid @RequestBody CreditNoteCancelRequest request) {
        return ResponseEntity.ok(creditNoteService.cancel(id, request.getReason()));
    }

    @PostMapping("/{id}/refund")
    @Operation(summary = "Record a refund payout", description = "Cash paid out against this credit note's refundable balance. Writes a REFUND-direction payment receipt.")
    public ResponseEntity<CreditNoteResponse> refund(@PathVariable String id, @Valid @RequestBody CreditNoteRefundRequest request) {
        return ResponseEntity.ok(creditNoteService.refund(id, request));
    }
}
