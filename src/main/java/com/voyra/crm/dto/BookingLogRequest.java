package com.voyra.crm.dto;

import com.voyra.crm.enums.BookingType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Schema(description = "The 'booking' part of POST /api/bookings/log. With serviceId, client, type, "
        + "destination, the service's own details, the agent and the travellers are all taken from "
        + "the lead's service and the matching request fields are ignored. Without it (an ad-hoc "
        + "booking) clientId, type and destination are required.")
public class BookingLogRequest implements com.voyra.crm.models.ForeignPriced {

    @Schema(description = "The lead's service this booking confirms. Omit for an ad-hoc booking.", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String serviceId;

    @Schema(description = "Service bookings only: which lead travellers are on it. Omit to include every traveller not DROPPED.")
    private List<String> leadMemberIds;

    @Schema(description = "Ad-hoc only: the client billed.", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String clientId;

    @Schema(description = "Owner-only: the agent the booking is attributed to. For a service booking the service's assignee wins when set.", example = "CB9Y0N")
    private String agentId;

    @Schema(description = "Ad-hoc only.", example = "PACKAGE")
    private BookingType type;

    @Schema(description = "Ad-hoc only.", example = "Dubai, UAE")
    private String destination;

    @Schema(description = "Ad-hoc only.", example = "2026-11-12")
    private LocalDate journeyDate;

    @Schema(description = "Ad-hoc only.", example = "2026-11-18")
    private LocalDate returnDate;

    @Schema(description = "FLIGHT only: whether the itinerary leaves India.", defaultValue = "false")
    private Boolean internationalTrip;

    @NotBlank(message = "Booking reference is required")
    @jakarta.validation.constraints.Size(max = 100, message = "Booking reference must be 100 characters or fewer")
    @Schema(description = "PNR, hotel confirmation no., visa application no. or transfer voucher no. - stored in the column for the booking's type.", example = "ABCXYZ")
    private String reference;

    @NotBlank(message = "Supplier is required")
    @Schema(description = "Supplier vendor; a vendor with a net cost drafts a supplier bill.", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String vendorId;

    @NotNull(message = "Net cost is required")
    @DecimalMin(value = "0.0", message = "Net cost cannot be negative")
    @Schema(example = "42000.00")
    private BigDecimal netCost;

    @DecimalMin(value = "0.0", message = "Selling price cannot be negative")
    @Schema(description = "Only for API clients that price by hand - with markupMode set, the price is worked out from netCost and the markup instead", example = "52000.00")
    private BigDecimal sellingPrice;

    @Schema(description = "Owner only: save even though it takes the client over their credit limit", example = "false")
    private Boolean overrideCreditLimit;

    @Schema(description = "PERCENT or FLAT: the selling price is netCost plus this markup", example = "PERCENT")
    private com.voyra.crm.enums.MarkupMode markupMode;

    @DecimalMin(value = "0.0", message = "Markup cannot be negative")
    @Schema(example = "10.00")
    private BigDecimal markupValue;

    @jakarta.validation.constraints.Size(max = 3, message = "Purchase currency must be a 3-letter code")
    @Schema(description = "Currency the purchase price is in (ISO 4217); blank means the agency's base currency", example = "AED")
    private String purchaseCurrency;

    @jakarta.validation.constraints.DecimalMin(value = "0.0", message = "Purchase price cannot be negative")
    @Schema(description = "What the supplier charges, in purchaseCurrency. When set, netCost is worked out from it at purchaseRate", example = "1000.00")
    private java.math.BigDecimal purchasePrice;

    @jakarta.validation.constraints.DecimalMin(value = "0.0", message = "Purchase rate cannot be negative")
    @Schema(description = "What 1 unit of purchaseCurrency is worth in base on the purchase date; required when it isn't the base", example = "22.85")
    private java.math.BigDecimal purchaseRate;

    @jakarta.validation.constraints.Size(max = 3, message = "Markup currency must be a 3-letter code")
    @Schema(description = "Currency a FLAT markupInput is in (ISO 4217); blank means base", example = "AED")
    private String markupCurrency;

    @jakarta.validation.constraints.DecimalMin(value = "0.0", message = "Markup cannot be negative")
    @Schema(description = "A FLAT markup as typed, in markupCurrency. When set, markupValue is worked out from it at markupRate", example = "100.00")
    private java.math.BigDecimal markupInput;

    @jakarta.validation.constraints.DecimalMin(value = "0.0", message = "Markup rate cannot be negative")
    @Schema(description = "What 1 unit of markupCurrency is worth in base; required when it isn't the base", example = "22.85")
    private java.math.BigDecimal markupRate;

    @Schema(example = "Window seats requested")
    private String notes;

}
