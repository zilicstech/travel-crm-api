package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.voyra.crm.util.ValidationPatterns;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "Patch semantics: a null field is left unchanged. agencyName/ownerName/"
        + "ownerEmail are not editable here - they are set at onboarding by the platform.")
public class AgencyProfileUpdateRequest {

    @jakarta.validation.constraints.Pattern(regexp = ValidationPatterns.GSTIN, message = ValidationPatterns.GSTIN_MESSAGE)
    @Schema(example = "27AABCU9603R1ZM")
    private String gstNumber;

    @Schema(example = "42 MG Road, Bengaluru")
    private String address;

    @Schema(example = "₹ (INR)")
    private String currency;

    @jakarta.validation.constraints.DecimalMin(value = "0", message = "Default commission can't be negative")
    @jakarta.validation.constraints.DecimalMax(value = "100", message = "Default commission can't be over 100%")
    @Schema(example = "2.00")
    private BigDecimal defaultCommission;

    @Schema(example = "27")
    private String stateCode;

    @Schema(example = "INR")
    private String baseCurrencyCode;

    @Schema(example = "9985")
    private String defaultSacCode;

    @Schema(example = "Payment due within 7 days of invoice date.")
    private String invoiceTerms;

    @Schema(example = "Global Explorer Travels Pvt Ltd")
    private String legalName;

    @Schema(description = "Printed on every customer invoice's bank block", example = "Global Explorer Travels Pvt Ltd")
    private String bankAccountName;

    @Schema(example = "005505015417")
    private String bankAccountNumber;

    @jakarta.validation.constraints.Pattern(regexp = ValidationPatterns.IFSC, message = ValidationPatterns.IFSC_MESSAGE)
    @Schema(example = "ICIC0000055")
    private String bankIfscCode;

    @Schema(example = "MG Road branch, Bengaluru")
    private String bankBranch;

    @Schema(description = "Printed in the invoice header", example = "9993341873")
    @jakarta.validation.constraints.Size(max = 30, message = "Phone must be 30 characters or fewer")
    private String contactPhone;

    @Schema(description = "Printed in the invoice header", example = "info@tourwayz.com")
    @jakarta.validation.constraints.Size(max = 150, message = "Email must be 150 characters or fewer")
    private String contactEmail;

    @Schema(description = "Printed in the invoice header", example = "www.tourwayz.com")
    @jakarta.validation.constraints.Size(max = 150, message = "Website must be 150 characters or fewer")
    private String website;
}
