package com.voyra.crm.dto;

import com.voyra.crm.enums.CreditNoteReason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Schema(description = "Drafts a credit note against an issued tax invoice. cancellationFee is the slice of the invoice's taxable value the agency retains and that stays taxable - leave it at zero for a full credit; everything else is reversed pro-rata at the invoice's own persisted CGST/SGST/IGST/TCS amounts.")
public class CreditNoteRequest {

    @NotBlank(message = "invoiceId is required")
    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String invoiceId;

    @NotNull(message = "reason is required")
    @Schema(example = "BOOKING_CANCELLED")
    private CreditNoteReason reason;

    @Schema(example = "Client cancelled 10 days before departure")
    private String reasonNote;

    @DecimalMin(value = "0", message = "cancellationFee cannot be negative")
    @Schema(description = "Retained taxable value, not credited. Zero means the full invoice is credited.", example = "0.00")
    private BigDecimal cancellationFee;

    @Schema(description = "Defaults to today when omitted", example = "2026-09-19")
    private LocalDate noteDate;
}
