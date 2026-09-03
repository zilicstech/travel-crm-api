package com.voyra.crm.controller;

import com.voyra.crm.dto.VoucherCreateRequest;
import com.voyra.crm.dto.VoucherResponse;
import com.voyra.crm.service.LeadVoucherService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** PNRs and supplier references on a lead, optionally tied to one service instance. */
@Slf4j
@RestController
@RequestMapping("/api/leads/{leadId}/vouchers")
@RequiredArgsConstructor
@Tag(name = "Lead Vouchers", description = "PNRs and supplier references on a lead")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT')")
public class LeadVoucherController {

    private final LeadVoucherService leadVoucherService;

    @PostMapping
    @Operation(summary = "Add a voucher")
    public ResponseEntity<VoucherResponse> addVoucher(@PathVariable String leadId,
                                                       @Valid @RequestBody VoucherCreateRequest request) {
        return ResponseEntity.ok(leadVoucherService.addVoucher(leadId, request));
    }

    @GetMapping
    @Operation(summary = "This lead's vouchers")
    public ResponseEntity<List<VoucherResponse>> listVouchers(@PathVariable String leadId) {
        return ResponseEntity.ok(leadVoucherService.listForLead(leadId));
    }

    @DeleteMapping("/{voucherId}")
    @Operation(summary = "Remove a voucher")
    public ResponseEntity<Void> deleteVoucher(@PathVariable String leadId, @PathVariable String voucherId) {
        leadVoucherService.deleteVoucher(leadId, voucherId);
        return ResponseEntity.noContent().build();
    }
}
