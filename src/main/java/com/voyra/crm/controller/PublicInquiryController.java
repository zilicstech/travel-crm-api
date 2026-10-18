package com.voyra.crm.controller;

import com.voyra.crm.dto.InquiryCreateRequest;
import com.voyra.crm.dto.SimpleAckResponse;
import com.voyra.crm.service.InquiryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Unauthenticated marketing-site form submissions (the /api/public/** permitAll surface). */
@RestController
@RequestMapping("/api/public/inquiries")
@RequiredArgsConstructor
@Tag(name = "Public - Inquiries", description = "Unauthenticated sales inquiries from the marketing site")
public class PublicInquiryController {

    private final InquiryService inquiryService;

    @PostMapping
    @Operation(summary = "Submit a sales inquiry", description = "No auth required. Email or phone is required. "
            + "A filled honeypot field is accepted with the same response but not stored.")
    public ResponseEntity<SimpleAckResponse> submit(@Valid @RequestBody InquiryCreateRequest request) {
        inquiryService.submit(request);
        return ResponseEntity.ok(SimpleAckResponse.builder().success(true).message("Inquiry received").build());
    }
}
