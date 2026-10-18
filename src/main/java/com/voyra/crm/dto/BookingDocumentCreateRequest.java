package com.voyra.crm.dto;

import com.voyra.crm.enums.BookingDocumentType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
@Schema(description = "A voucher document to attach to a booking - an e-ticket, a hotel voucher, "
        + "an insurance certificate. The file itself is uploaded separately via the /file endpoint.")
public class BookingDocumentCreateRequest {

    @NotNull(message = "Document type is required")
    @Schema(example = "E_TICKET")
    private BookingDocumentType docType;

    @Schema(example = "0987654321")
    private String referenceNumber;

    @Schema(example = "2026-09-20")
    private LocalDate issuedDate;

    @Schema(example = "Reissued after the airline's schedule change")
    private String notes;

    @jakarta.validation.constraints.DecimalMin(value = "0.0", message = "Net cost cannot be negative")
    @Schema(description = "What the supplier charges for this voucher - added to the booking's net cost", example = "4000.00")
    private java.math.BigDecimal netCost;

    @Schema(description = "PERCENT or FLAT - the selling price is worked out from the cost and this markup", example = "PERCENT")
    private com.voyra.crm.enums.MarkupMode markupMode;

    @jakarta.validation.constraints.DecimalMin(value = "0.0", message = "Markup cannot be negative")
    @Schema(example = "10.00")
    private java.math.BigDecimal markupValue;

    @Schema(description = "Owner only: save even though it takes the client over their credit limit", example = "false")
    private Boolean overrideCreditLimit;

    @Schema(description = "The supplier (Vendor) of this voucher; defaults to the booking's. With a cost, drafts its supplier bill.")
    private String vendorId;

    @Schema(description = "This voucher's travel details, flat keys by booking type (e.g. airline, flightNumber, flightFrom, "
            + "flightTo, journeyDate, flightDepartTime for a flight; hotelName, hotelCheckIn, hotelCheckOut for a hotel). "
            + "Unknown keys and blank values are dropped.", example = "{\"airline\": \"IndiGo\", \"flightNumber\": \"6E 203\"}")
    private java.util.Map<String, String> details;
}
