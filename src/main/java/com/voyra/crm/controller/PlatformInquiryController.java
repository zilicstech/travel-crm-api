package com.voyra.crm.controller;

import com.voyra.crm.dto.InquiryResponse;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.service.InquiryService;
import com.voyra.crm.util.PageRequestUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/inquiries")
@RequiredArgsConstructor
@Tag(name = "Platform - Inquiries", description = "SUPER_ADMIN read of marketing-site sales inquiries")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class PlatformInquiryController {

    private final InquiryService inquiryService;

    @GetMapping
    @Operation(summary = "List inquiries, newest first",
            description = "Always paged (page defaults to 0). ?search= matches name, email, phone, company, message or source.")
    public ResponseEntity<PagedResponse<InquiryResponse>> list(
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        return ResponseEntity.ok(inquiryService.list(search, PageRequestUtil.resolve(page == null ? 0 : page, size)));
    }
}
