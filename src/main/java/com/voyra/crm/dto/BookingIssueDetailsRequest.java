package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
@Schema(description = "What was recorded by hand once the booking was issued. The whole set is replaced: null or blank "
        + "clears a field. People are the owner (user id = agency id) or one of the agency's staff.")
public class BookingIssueDetailsRequest {

    @Schema(description = "Who assigned the work", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String assignedById;

    @Schema(description = "Who issued it", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String issuedById;

    @Schema(description = "Who actioned it", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String actionedById;

    @Pattern(regexp = "^([A-Z]{3})?$", message = "Use a 3-letter currency code")
    @Schema(example = "AED")
    private String purchaseCurrency;

    @Pattern(regexp = "^([A-Z]{3})?$", message = "Use a 3-letter currency code")
    @Schema(example = "INR")
    private String sellingCurrency;
}
