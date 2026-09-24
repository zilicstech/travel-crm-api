package com.voyra.crm.dto;

import com.voyra.crm.enums.FxRateSource;
import com.voyra.crm.enums.InvoiceDocumentType;
import com.voyra.crm.enums.InvoiceLifecycle;
import com.voyra.crm.enums.InvoiceServiceCategory;
import com.voyra.crm.enums.SupplyNature;
import com.voyra.crm.enums.TaxTreatment;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Full invoice document with its line items. Every tax/currency field is exactly what was computed and, once status leaves DRAFT, frozen.")
public class InvoiceResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(description = "Null while DRAFT", example = "INV/2026-27/0001")
    private String invoiceNumber;

    @Schema(example = "2026-27")
    private String financialYear;

    @Schema(example = "TAX_INVOICE")
    private InvoiceDocumentType documentType;

    @Schema(description = "Drives the number series, printed title and print template")
    private InvoiceServiceCategory serviceCategory;

    @Schema(example = "ISSUED")
    private InvoiceLifecycle status;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String clientId;

    @Schema(example = "Arjun Mehta")
    private String clientName;

    @Schema(example = "27AABCU9603R1ZM")
    private String clientGstin;

    @Schema(example = "27")
    private String clientStateCode;

    @Schema(example = "42 MG Road, Bengaluru")
    private String billingAddress;

    @Schema(example = "Global Explorer Travels Pvt Ltd")
    private String agencyLegalName;

    @Schema(example = "27AABCU9603R1ZM")
    private String agencyGstin;

    @Schema(example = "27")
    private String agencyStateCode;

    @Schema(example = "1 Link Road, Mumbai")
    private String agencyAddress;

    @Schema(example = "8f2a1c3d-58cc-4372-a567-0e02b2c3d479")
    private String bookingId;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String leadId;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String agentId;

    @Schema(example = "27")
    private String placeOfSupplyCode;

    @Schema(example = "DOMESTIC_PACKAGE")
    private SupplyNature supplyNature;

    @Schema(example = "INTRA_STATE")
    private TaxTreatment taxTreatment;

    @Schema(example = "27")
    private String placeOfSupplyOverride;

    @Schema(example = "false")
    private Boolean exportOfServiceRequested;

    @Schema(example = "INR")
    private String currencyCode;

    @Schema(example = "1.000000")
    private BigDecimal fxRateToInr;

    @Schema(example = "MANUAL")
    private FxRateSource fxRateSource;

    @Schema(example = "2026-09-19T10:15:00")
    private LocalDateTime fxLockedAt;

    @Schema(example = "84000.00")
    private BigDecimal subtotal;

    @Schema(example = "0.00")
    private BigDecimal discountTotal;

    @Schema(example = "84000.00")
    private BigDecimal taxableValue;

    @Schema(example = "2100.00")
    private BigDecimal cgstAmount;

    @Schema(example = "2100.00")
    private BigDecimal sgstAmount;

    @Schema(example = "0.00")
    private BigDecimal igstAmount;

    @Schema(example = "4200.00")
    private BigDecimal gstTotal;

    @Schema(example = "0.000")
    private BigDecimal tcsRatePercent;

    @Schema(example = "206C(1G)")
    private String tcsSection;

    @Schema(example = "0.00")
    private BigDecimal tcsBaseAmount;

    @Schema(example = "0.00")
    private BigDecimal tcsAmount;

    @Schema(example = "0.00")
    private BigDecimal roundOff;

    @Schema(example = "88200.00")
    private BigDecimal grandTotal;

    @Schema(example = "84000.00")
    private BigDecimal taxableValueInr;

    @Schema(example = "4200.00")
    private BigDecimal gstTotalInr;

    @Schema(example = "0.00")
    private BigDecimal tcsAmountInr;

    @Schema(example = "88200.00")
    private BigDecimal grandTotalInr;

    @Schema(example = "0.00")
    private BigDecimal amountReceived;

    @Schema(example = "0.00")
    private BigDecimal creditNoteTotal;

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

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String issuedBy;

    @Schema(example = "2026-09-20T09:00:00")
    private LocalDateTime cancelledAt;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String cancelledBy;

    @Schema(example = "Raised in error")
    private String cancelReason;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String supersedesInvoiceId;

    @Schema(example = "Thank you for booking with us.")
    private String notes;

    @Schema(example = "Payment due within 7 days of invoice date.")
    private String terms;

    @Schema(description = "Every line, sort order ascending")
    private List<InvoiceLineItemResponse> lines;
}
