package com.voyra.crm.dto;

import com.voyra.crm.enums.ServiceType;
import io.swagger.v3.oas.annotations.media.Schema;
import com.voyra.crm.util.ValidationPatterns;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Schema(description = "Request body for adding a new vendor")
public class VendorCreateRequest {

    @NotBlank(message = "Vendor name is required")
    @Size(max = 150, message = "Vendor name must be at most 150 characters")
    @Schema(example = "Tripjack")
    private String name;

    @NotEmpty(message = "Pick at least one service type")
    @Schema(description = "Which service types this vendor supplies", example = "[\"FLIGHT\", \"HOTEL\"]")
    private List<ServiceType> serviceTypes;

    @Schema(example = "Rohan Mehta")
    private String contactPerson;

    @Schema(example = "+91 98765 43210")
    private String phone;

    @Email(message = "Enter a valid email address")
    @Schema(example = "bookings@tripjack.com")
    private String email;

    @Schema(example = "12th Floor, DLF Cyber City, Gurugram")
    private String address;

    @Pattern(regexp = "^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$", message = "Enter a valid 15-character GSTIN")
    @Schema(example = "07AAACT2727Q1ZW")
    private String gstNumber;

    @Schema(example = "Preferred consolidator for domestic flights")
    private String notes;

    @Schema(example = "8% commission, net 15")
    private String defaultRateNote;

    @Schema(example = "07")
    private String stateCode;

    @Pattern(regexp = "^[A-Z]{5}[0-9]{4}[A-Z]$", message = "Enter a valid 10-character PAN")
    @Schema(example = "AAACT2727Q")
    private String panNumber;

    @Schema(example = "false")
    private Boolean isPrepaid;

    @jakarta.validation.constraints.PositiveOrZero(message = "Payment terms can't be negative")
    @Schema(example = "15")
    private Integer paymentTermsDays;

    @jakarta.validation.constraints.PositiveOrZero(message = "Credit limit can't be negative")
    @Schema(example = "500000.00")
    private BigDecimal creditLimitInr;

    @Schema(example = "150000.00")
    private BigDecimal lowBalanceThresholdInr;

    @Schema(example = "194C")
    private String tdsSection;

    @jakarta.validation.constraints.DecimalMin(value = "0", message = "TDS rate can't be negative")
    @jakarta.validation.constraints.DecimalMax(value = "100", message = "TDS rate can't be over 100%")
    @Schema(example = "2.000")
    private BigDecimal tdsRatePercent;

    @Schema(example = "Tripjack Travels Pvt Ltd")
    private String bankAccountName;

    @Schema(example = "000123456789")
    private String bankAccountNumber;

    @Pattern(regexp = ValidationPatterns.IFSC, message = ValidationPatterns.IFSC_MESSAGE)
    @Schema(example = "HDFC0000123")
    private String bankIfsc;
}
