package com.voyra.crm.dto;

import com.voyra.crm.enums.BookingDocumentType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
@Schema(description = "A voucher document to attach to a booking - an e-ticket, a hotel voucher, "
        + "an insurance certificate. The file itself is uploaded separately via the /file endpoint.")
public class BookingDocumentCreateRequest implements com.voyra.crm.models.ForeignPriced {

    @NotNull(message = "Document type is required")
    @Schema(example = "E_TICKET")
    private BookingDocumentType docType;

    @jakarta.validation.constraints.NotBlank(message = "Reference is required")
    @jakarta.validation.constraints.Size(max = 100, message = "Reference must be 100 characters or fewer")
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

    @Schema(description = "Owner only: save even though it takes the client over their credit limit", example = "false")
    private Boolean overrideCreditLimit;

    @Schema(description = "The supplier (Vendor) of this voucher; defaults to the booking's. With a cost, drafts its supplier bill.")
    private String vendorId;

    @Schema(description = "This voucher's travel details, flat keys by booking type (e.g. airline, flightNumber, flightFrom, "
            + "flightTo, journeyDate, flightDepartTime for a flight; hotelName, hotelCheckIn, hotelCheckOut for a hotel). "
            + "Unknown keys and blank values are dropped.", example = "{\"airline\": \"IndiGo\", \"flightNumber\": \"6E 203\"}")
    private java.util.Map<String, String> details;

    @Schema(description = "An air ticket's travellers - the client's members, by memberId (required for an e-ticket on a flight booking)")
    private java.util.List<VoucherTraveller> travellers;

    @Schema(description = "Flight bookings: Domestic (false) or International (true) - sets the booking's trip type, which picks the "
            + "invoice series and title; it can't change once the booking is on a draft or issued invoice", example = "false")
    private Boolean internationalTrip;

    @Schema(description = "An air ticket's flights in order, one or more (required for an e-ticket on a flight booking). "
            + "Its flat flight details are worked out from them.")
    private java.util.List<VoucherLeg> legs;

}
