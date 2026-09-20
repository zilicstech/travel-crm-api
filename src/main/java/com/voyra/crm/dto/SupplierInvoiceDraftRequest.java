package com.voyra.crm.dto;

import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.ItcEligibility;
import com.voyra.crm.enums.SupplierInvoiceKind;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Schema(description = "Creates or replaces a DRAFT supplier bill's header and its whole line list. "
        + "Every GST figure here is RECORDED from the supplier's own document - approving validates it "
        + "against vendor.stateCode, it is never computed by our own TaxEngine.")
public class SupplierInvoiceDraftRequest {

    @NotBlank(message = "vendorId is required")
    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String vendorId;

    @Schema(example = "PURCHASE", defaultValue = "PURCHASE")
    private SupplierInvoiceKind kind;

    @NotNull(message = "category is required")
    @Schema(example = "FLIGHT")
    private BookingType category;

    @Schema(example = "IND/2026/48213")
    private String supplierInvoiceNumber;

    @Schema(example = "07AAACT2727Q1ZW")
    private String supplierGstin;

    @NotNull(message = "invoiceDate is required")
    @Schema(example = "2026-09-18")
    private LocalDate invoiceDate;

    @Schema(example = "2026-09-19")
    private LocalDate receivedOn;

    @Schema(description = "One bill per booking - the booking this bill is for", example = "8f2a1c3d-58cc-4372-a567-0e02b2c3d479")
    private String bookingId;

    @Schema(example = "PNR ABC123")
    private String referenceNote;

    @Schema(example = "INR", defaultValue = "INR")
    private String currencyCode;

    @Schema(description = "Required when currencyCode is not INR", example = "83.120000")
    private BigDecimal fxRateToInr;

    @Schema(example = "ELIGIBLE", defaultValue = "ELIGIBLE")
    private ItcEligibility itcEligibility;

    @Schema(example = "5% slab, no ITC")
    private String itcNote;

    @Schema(example = "false", defaultValue = "false")
    private Boolean isReverseCharge;

    @Schema(example = "194C")
    private String tdsSection;

    @Schema(example = "2.000")
    private BigDecimal tdsRatePercent;

    @Schema(example = "Advance booking, GST invoice to follow")
    private String notes;

    @Valid
    @Schema(description = "The whole line list - a save replaces every existing line with this set")
    private List<SupplierInvoiceLineItemRequest> lines;
}
