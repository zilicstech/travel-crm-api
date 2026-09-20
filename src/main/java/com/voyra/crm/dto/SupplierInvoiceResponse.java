package com.voyra.crm.dto;

import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.ItcEligibility;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.enums.SupplierInvoiceKind;
import com.voyra.crm.enums.SupplierInvoiceStatus;
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
@Schema(description = "Full supplier bill with its line items - accounts-payable document")
public class SupplierInvoiceResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(description = "Null for a legacy row whose original supplier name matched no vendor - shown as unlinked", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String vendorId;

    @Schema(example = "IndiGo")
    private String vendorName;

    @Schema(example = "PURCHASE")
    private SupplierInvoiceKind kind;

    @Schema(example = "FLIGHT")
    private BookingType category;

    @Schema(example = "APPROVED")
    private SupplierInvoiceStatus status;

    @Schema(example = "IND/2026/48213")
    private String supplierInvoiceNumber;

    @Schema(example = "07AAACT2727Q1ZW")
    private String supplierGstin;

    @Schema(example = "07")
    private String supplierStateCode;

    @Schema(example = "2026-09-18")
    private LocalDate invoiceDate;

    @Schema(example = "2026-09-19")
    private LocalDate receivedOn;

    @Schema(example = "2026-10-03")
    private LocalDate dueDate;

    @Schema(example = "8f2a1c3d-58cc-4372-a567-0e02b2c3d479")
    private String bookingId;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String leadId;

    @Schema(example = "FLIGHT")
    private ServiceType serviceType;

    @Schema(example = "PNR ABC123")
    private String referenceNote;

    @Schema(example = "INR")
    private String currencyCode;

    @Schema(example = "1.000000")
    private BigDecimal fxRateToInr;

    @Schema(example = "MANUAL")
    private String fxRateSource;

    @Schema(example = "12400.00")
    private BigDecimal subtotal;

    @Schema(example = "0.00")
    private BigDecimal discountTotal;

    @Schema(example = "12400.00")
    private BigDecimal taxableValue;

    @Schema(example = "310.00")
    private BigDecimal cgstAmount;

    @Schema(example = "310.00")
    private BigDecimal sgstAmount;

    @Schema(example = "0.00")
    private BigDecimal igstAmount;

    @Schema(example = "620.00")
    private BigDecimal gstTotal;

    @Schema(example = "0.00")
    private BigDecimal cessAmount;

    @Schema(example = "0.00")
    private BigDecimal roundOff;

    @Schema(example = "13020.00")
    private BigDecimal grandTotal;

    @Schema(example = "ELIGIBLE")
    private ItcEligibility itcEligibility;

    @Schema(example = "5% slab, no ITC")
    private String itcNote;

    @Schema(example = "false")
    private Boolean isReverseCharge;

    @Schema(example = "194C")
    private String tdsSection;

    @Schema(example = "2.000")
    private BigDecimal tdsRatePercent;

    @Schema(example = "260.40")
    private BigDecimal tdsAmount;

    @Schema(example = "12400.00")
    private BigDecimal taxableValueInr;

    @Schema(example = "620.00")
    private BigDecimal gstTotalInr;

    @Schema(example = "13020.00")
    private BigDecimal grandTotalInr;

    @Schema(example = "0.00")
    private BigDecimal amountPaid;

    @Schema(example = "0.00")
    private BigDecimal creditNoteTotal;

    @Schema(example = "13020.00")
    private BigDecimal balanceDue;

    @Schema(example = "13020.00")
    private BigDecimal balanceDueInr;

    @Schema(example = "2026-09-19T10:15:00")
    private LocalDateTime approvedAt;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String approvedBy;

    @Schema(example = "2026-09-20T09:00:00")
    private LocalDateTime cancelledAt;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String cancelledBy;

    @Schema(example = "Duplicate entry")
    private String cancelReason;

    @Schema(example = "Advance booking, GST invoice to follow")
    private String notes;

    @Schema(example = "true")
    private Boolean hasFile;

    @Schema(example = "2026-09-19T10:00:00")
    private LocalDateTime createdDate;

    @Schema(description = "Every line, sort order ascending")
    private List<SupplierInvoiceLineItemResponse> lines;
}
