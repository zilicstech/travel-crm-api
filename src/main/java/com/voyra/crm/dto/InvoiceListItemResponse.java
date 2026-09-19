package com.voyra.crm.dto;

import com.voyra.crm.enums.InvoiceDocumentType;
import com.voyra.crm.enums.InvoiceLifecycle;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One row of the billing worklist / invoice list - no line items, see InvoiceResponse for the full document")
public class InvoiceListItemResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(description = "Null while DRAFT", example = "INV/2026-27/0001")
    private String invoiceNumber;

    @Schema(example = "2026-27")
    private String financialYear;

    @Schema(example = "TAX_INVOICE")
    private InvoiceDocumentType documentType;

    @Schema(example = "ISSUED")
    private InvoiceLifecycle status;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String clientId;

    @Schema(example = "Arjun Mehta")
    private String clientName;

    @Schema(example = "8f2a1c3d-58cc-4372-a567-0e02b2c3d479")
    private String bookingId;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String agentId;

    @Schema(example = "INR")
    private String currencyCode;

    @Schema(example = "88200.00")
    private BigDecimal grandTotal;

    @Schema(example = "88200.00")
    private BigDecimal grandTotalInr;

    @Schema(example = "88200.00")
    private BigDecimal balanceDue;

    @Schema(example = "88200.00")
    private BigDecimal balanceDueInr;

    @Schema(example = "2026-09-19")
    private LocalDate invoiceDate;

    @Schema(example = "2026-09-26")
    private LocalDate dueDate;

    @Schema(example = "2026-09-19T10:15:00")
    private LocalDateTime issuedAt;
}
