package com.voyra.crm.dto;

import com.voyra.crm.enums.SupplierCreditNoteReason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Schema(description = "Records a credit note the SUPPLIER issued to us against an approved bill. "
        + "Figures are the supplier's own, recorded verbatim (like a supplier bill). retentionFee is the "
        + "supplier's own cancellation charge, held back from the credit.")
public class SupplierCreditNoteRequest {

    @NotBlank(message = "supplierInvoiceId is required")
    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String supplierInvoiceId;

    @Schema(example = "IND-CN/2026/00812")
    private String supplierNoteNumber;

    @NotNull(message = "reason is required")
    @Schema(example = "BOOKING_CANCELLED")
    private SupplierCreditNoteReason reason;

    @Schema(example = "Client cancelled, supplier waived 80%")
    private String reasonNote;

    @NotNull(message = "taxableValue is required")
    @DecimalMin(value = "0", message = "taxableValue cannot be negative")
    @Schema(example = "10400.00")
    private BigDecimal taxableValue;

    @NotNull(message = "cgstAmount is required")
    @Schema(example = "260.00")
    private BigDecimal cgstAmount;

    @NotNull(message = "sgstAmount is required")
    @Schema(example = "260.00")
    private BigDecimal sgstAmount;

    @NotNull(message = "igstAmount is required")
    @Schema(example = "0.00")
    private BigDecimal igstAmount;

    @DecimalMin(value = "0", message = "retentionFee cannot be negative")
    @Schema(description = "The supplier's own cancellation charge, held back from the credit", example = "2000.00")
    private BigDecimal retentionFee;

    @Schema(description = "Defaults to today when omitted", example = "2026-09-19")
    private LocalDate noteDate;
}
