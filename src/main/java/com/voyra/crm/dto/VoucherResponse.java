package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One PNR or supplier reference on a lead")
public class VoucherResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String leadId;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String serviceId;

    @Schema(description = "Snapshot of the service's label when this voucher was added", example = "Flight — BOM–BKK")
    private String serviceLabel;

    @Schema(example = "Emirates")
    private String supplier;

    @Schema(example = "PNR8X4Y1")
    private String referenceNumber;

    @Schema(example = "2026-09-20")
    private LocalDate voucherDate;

    @Schema(example = "Issued via the Emirates GDS")
    private String notes;

    @Schema(example = "2026-08-13T09:15:22")
    private LocalDateTime createdAt;
}
