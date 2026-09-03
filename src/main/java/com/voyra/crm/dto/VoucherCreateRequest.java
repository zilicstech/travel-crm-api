package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
@Schema(description = "A PNR or supplier reference. Set serviceId to tie it to one service "
        + "instance - a PNR is a fact about the flight it belongs to.")
public class VoucherCreateRequest {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String serviceId;

    @NotBlank(message = "Supplier is required")
    @Schema(example = "Emirates")
    private String supplier;

    @NotBlank(message = "Reference number is required")
    @Schema(example = "PNR8X4Y1")
    private String referenceNumber;

    @NotNull(message = "Voucher date is required")
    @Schema(example = "2026-09-20")
    private LocalDate voucherDate;

    @Schema(example = "Issued via the Emirates GDS")
    private String notes;
}
