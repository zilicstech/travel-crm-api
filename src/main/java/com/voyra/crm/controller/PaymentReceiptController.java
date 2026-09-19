package com.voyra.crm.controller;

import com.voyra.crm.dto.PaymentReceiptRequest;
import com.voyra.crm.dto.PaymentReceiptResponse;
import com.voyra.crm.dto.PaymentReceiptReverseRequest;
import com.voyra.crm.service.PaymentReceiptService;
import com.voyra.crm.util.PageRequestUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * Payment receipts - append-only. An Agent may read receipts scoped to their own bookings'
 * invoices; recording and reversing are Owner/Accountant only.
 */
@Slf4j
@RestController
@RequestMapping("/api/accounts/receipts")
@RequiredArgsConstructor
@Tag(name = "Accounts - Receipts", description = "Payment receipts against invoices and issued proformas")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT', 'AGENT')")
public class PaymentReceiptController {

    private final PaymentReceiptService paymentReceiptService;

    @PostMapping
    @PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT')")
    @Operation(summary = "Record a receipt", description = "Currency and FX rate are always the invoice's own - a payment in another currency is rejected.")
    public ResponseEntity<PaymentReceiptResponse> record(@Valid @RequestBody PaymentReceiptRequest request) {
        return ResponseEntity.ok(paymentReceiptService.record(request));
    }

    @GetMapping
    @Operation(summary = "List receipts", description = "Supply ?page= for a paged envelope; omit it for the full list as a plain array. An Agent sees only their own bookings' invoices' receipts.")
    public ResponseEntity<Object> list(
            @RequestParam(value = "invoiceId", required = false) String invoiceId,
            @RequestParam(value = "clientId", required = false) String clientId,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        Pageable pageable = PageRequestUtil.resolve(page, size);
        return ResponseEntity.ok(paymentReceiptService.list(invoiceId, clientId, pageable));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one receipt")
    public ResponseEntity<PaymentReceiptResponse> get(@PathVariable String id) {
        return ResponseEntity.ok(paymentReceiptService.get(id));
    }

    @PostMapping("/{id}/reverse")
    @PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT')")
    @Operation(summary = "Reverse a receipt", description = "Writes a new, opposite-signed receipt - the original is never edited or deleted.")
    public ResponseEntity<PaymentReceiptResponse> reverse(@PathVariable String id, @Valid @RequestBody PaymentReceiptReverseRequest request) {
        return ResponseEntity.ok(paymentReceiptService.reverse(id, request.getReason()));
    }

    @GetMapping("/{id}/pdf")
    @Operation(summary = "Render this receipt as a voucher PDF")
    public ResponseEntity<byte[]> pdf(@PathVariable String id) {
        PaymentReceiptResponse receipt = paymentReceiptService.get(id);
        byte[] pdf = paymentReceiptService.getPdf(id);
        String filename = (receipt.getReceiptNumber() != null ? receipt.getReceiptNumber() : "DRAFT-" + id.substring(0, 8)).replace("/", "-") + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
                .body(pdf);
    }
}
