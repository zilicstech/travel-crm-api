package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A partial edit of a visa application: null leaves a value as it is, an empty string clears a text value or a field.")
public class VisaValuesUpdateRequest {

    @NotNull(message = "Send the version you read")
    @Schema(description = "The application's version as read; a newer one on the server is 409 VISA_CHANGED", example = "3")
    private Long version;

    @Schema(description = "The case's overall status, set by hand (the checklist no longer sets it)", example = "SUBMITTED")
    private com.voyra.crm.enums.VisaStatus status;

    @Schema(description = "Field id -> new value; \"\" clears it (refused for a mandatory field)", example = "{\"f1\": \"RECVD\"}")
    private Map<String, String> values;

    @Size(max = 20, message = "Passport number must be 20 characters or fewer")
    @Schema(example = "M1234567")
    private String passportNumber;

    @Schema(example = "2031-04-30")
    private LocalDate passportExpiry;

    @Size(max = 60, message = "Nationality must be 60 characters or fewer")
    @Schema(example = "Indian")
    private String nationality;

    @DecimalMin(value = "0", message = "Purchase price cannot be negative")
    @Digits(integer = 10, fraction = 2, message = "Purchase price can have at most two decimals")
    @Schema(example = "120.00")
    private BigDecimal purchasePrice;

    @Pattern(regexp = "[A-Z]{3}", message = "Purchase currency is a 3-letter code")
    @Schema(example = "USD")
    private String purchaseCurrency;

    @DecimalMin(value = "0", message = "Selling price cannot be negative")
    @Digits(integer = 10, fraction = 2, message = "Selling price can have at most two decimals")
    @Schema(description = "In the agency's base currency", example = "12500.00")
    private BigDecimal sellingPrice;

    @Schema(description = "The supplier; \"\" clears it", example = "v-1")
    private String vendorId;

    @Size(max = 500, message = "Remarks must be 500 characters or fewer")
    @Schema(example = "Express processing")
    private String remarks;
}
