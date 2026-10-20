package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** "Purchase ?" on a line of the client's invoice: the supplier's bill for that voucher, booked with the invoice. */
@Data
@Schema(description = "The supplier's bill for one voucher on the invoice, booked when the invoice is generated")
public class PurchaseEntryRequest {

    @NotBlank(message = "The voucher is required")
    @Schema(description = "The voucher (booking document) whose supplier bill this is")
    private String bookingDocumentId;

    @NotBlank(message = "Enter the supplier's bill number")
    @Size(max = 100, message = "The supplier's bill number must be 100 characters or fewer")
    @Schema(example = "IND/2026/48213")
    private String supplierInvoiceNumber;

    @Schema(description = "The supplier's bill date; the draft's own when left out", example = "2026-10-20")
    private LocalDate invoiceDate;

    @Schema(description = "Flights: the supplier's commission, PERCENT of Basic or FLAT; left out keeps the voucher's", example = "PERCENT")
    private com.voyra.crm.enums.MarkupMode commissionMode;

    @DecimalMin(value = "0.0", message = "Commission cannot be negative")
    @Schema(example = "5")
    private BigDecimal commissionValue;

    @DecimalMin(value = "0.0", message = "TDS rate cannot be negative")
    @Schema(description = "TDS % the supplier deducts on the commission", example = "2")
    private BigDecimal supplierTdsRate;
}
