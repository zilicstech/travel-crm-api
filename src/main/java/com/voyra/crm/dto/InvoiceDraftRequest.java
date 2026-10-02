package com.voyra.crm.dto;

import com.voyra.crm.enums.InvoiceBillingModel;
import com.voyra.crm.enums.InvoiceServiceCategory;
import com.voyra.crm.enums.SupplyNature;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Schema(description = "Creates or replaces a DRAFT invoice's header and its whole line list in one call. "
        + "bookingId is required on create and ignored on an update (the booking link never changes). "
        + "Every tax field is recomputed from these inputs on every save - nothing here is trusted as a final amount.")
public class InvoiceDraftRequest {

    @Schema(description = "Required when creating; ignored when replacing an existing draft", example = "8f2a1c3d-...")
    private String bookingId;

    @Schema(description = "Overrides the category InvoiceServiceCategory.forBooking would derive from the "
            + "booking's own type - the only way to reach RAIL or MISCELLANEOUS, which have no BookingType "
            + "counterpart. Only read on create; a draft's category never changes on update.", example = "MISCELLANEOUS")
    private InvoiceServiceCategory serviceCategory;

    @Schema(description = "PRINCIPAL taxes the full package; COMMISSION_AGENT passes the booking's "
            + "supplier costs through untaxed and taxes only the service fee. Unset on create takes the "
            + "category's own default (air tickets: COMMISSION_AGENT, everything else: PRINCIPAL); unset "
            + "on update leaves the draft's current value unchanged.", example = "PRINCIPAL")
    private InvoiceBillingModel billingModel;

    @Schema(example = "INR", defaultValue = "INR")
    private String currencyCode;

    @Schema(description = "Required when currencyCode is not INR - the rate this invoice locks at issue", example = "83.120000")
    private BigDecimal fxRateToInr;

    @Schema(description = "Informational only now - tax is opt-in per invoice via `taxes` below, "
            + "never forced by supplyNature. Kept to pre-select a sensible default in the tax dropdown.", example = "DOMESTIC_PACKAGE")
    private SupplyNature supplyNature;

    @Schema(description = "Overrides the client's own state for place-of-supply", example = "27")
    private String placeOfSupplyCodeOverride;

    @Schema(description = "Explicit request for export-of-service treatment - only honoured when the client is overseas and the currency is non-INR")
    private Boolean exportOfServiceRequested;

    @Schema(example = "2026-10-05")
    private LocalDate dueDate;

    @Schema(example = "Thank you for booking with us.")
    private String notes;

    @Schema(example = "Payment due within 7 days of invoice date.")
    private String terms;

    @Valid
    @Schema(description = "The whole line list - a save replaces every existing line with this set")
    private List<InvoiceLineItemRequest> lines;

    @Valid
    @Schema(description = "Zero or more taxes to charge on this invoice - a save replaces the "
            + "whole set. Omit or send an empty list for no tax at all.")
    private List<InvoiceTaxRequest> taxes;
}
