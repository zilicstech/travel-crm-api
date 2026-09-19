package com.voyra.crm.dto;

import com.voyra.crm.enums.SupplyNature;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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

    @Schema(example = "INR", defaultValue = "INR")
    private String currencyCode;

    @Schema(description = "Required when currencyCode is not INR - the rate this invoice locks at issue", example = "83.120000")
    private BigDecimal fxRateToInr;

    @NotNull(message = "supplyNature is required")
    @Schema(example = "DOMESTIC_PACKAGE")
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
}
