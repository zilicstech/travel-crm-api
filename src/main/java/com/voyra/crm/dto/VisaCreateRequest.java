package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
@Schema(description = "Request body for creating a standalone Visa case")
public class VisaCreateRequest {

    @NotBlank(message = "Client is required")
    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String clientId;

    @Schema(description = "Ignored (kept for old clients): an owner opens the case in their own name, an agent in theirs.", example = "CB9Y0N")
    private String agentId;

    @Schema(description = "Optional traceability link back to the originating lead", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String leadId;

    @NotBlank(message = "Country is required")
    @Size(max = 100, message = "Country must be 100 characters or fewer")
    @Schema(example = "United Arab Emirates")
    private String country;

    @NotBlank(message = "Visa type is required")
    @Size(max = 50, message = "Visa type must be 50 characters or fewer")
    @Schema(example = "Tourist")
    private String visaType;

    @Size(max = 20, message = "Passport number must be 20 characters or fewer")
    @Schema(example = "M1234567")
    private String passportNumber;

    @Schema(example = "2026-08-15")
    private LocalDate applicationDate;

    @NotBlank(message = "Pick the traveller")
    @Schema(description = "The traveller, a member of the client; one application per traveller", example = "m-1")
    private String memberId;

    @Schema(description = "Defaults to the traveller's passport expiry", example = "2031-04-30")
    private LocalDate passportExpiry;

    @Size(max = 60, message = "Nationality must be 60 characters or fewer")
    @Schema(description = "Defaults to the traveller's nationality", example = "Indian")
    private String nationality;

    @Schema(description = "Configured field id -> value (Text, a Dropdown option, ISO date, \"true\"/\"false\"). File fields come as file parts named by field id.",
            example = "{\"f1\": \"RECVD\"}")
    private java.util.Map<String, String> values;

    @jakarta.validation.constraints.DecimalMin(value = "0", message = "Purchase price cannot be negative")
    @jakarta.validation.constraints.Digits(integer = 10, fraction = 2, message = "Purchase price can have at most two decimals")
    @Schema(example = "120.00")
    private java.math.BigDecimal purchasePrice;

    @jakarta.validation.constraints.Pattern(regexp = "[A-Z]{3}", message = "Purchase currency is a 3-letter code")
    @Schema(description = "Required with a purchase price", example = "USD")
    private String purchaseCurrency;

    @jakarta.validation.constraints.DecimalMin(value = "0", message = "Selling price cannot be negative")
    @jakarta.validation.constraints.Digits(integer = 10, fraction = 2, message = "Selling price can have at most two decimals")
    @Schema(description = "In the agency's base currency", example = "12500.00")
    private java.math.BigDecimal sellingPrice;

    @Schema(description = "The supplier (vendor) the visa is bought from", example = "v-1")
    private String vendorId;

    @Size(max = 500, message = "Remarks must be 500 characters or fewer")
    @Schema(example = "Express processing")
    private String remarks;
}
