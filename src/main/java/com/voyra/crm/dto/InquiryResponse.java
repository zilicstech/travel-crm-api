package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
@Schema(description = "A stored marketing-site sales inquiry")
public class InquiryResponse {

    @Schema(example = "6f1c2a4e-3b8d-4c1a-9e2f-7d5b0a1c3e9f")
    private String id;

    @Schema(description = "Which form sent it", example = "pricing-modal")
    private String source;

    @Schema(example = "John Davis")
    private String name;

    @Schema(example = "john@globalexplorer.com")
    private String email;

    @Schema(example = "+91 98765 43210")
    private String phone;

    @Schema(example = "Global Explorer Travels (8 agents)")
    private String company;

    @Schema(example = "Growth Plan")
    private String plan;

    @Schema(example = "We handle about 300 leads a month and need visa tracking.")
    private String message;

    @Schema(example = "2026-10-19T09:30:00Z")
    private Instant createdAt;
}
