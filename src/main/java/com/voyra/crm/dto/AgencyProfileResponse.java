package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "The signed-in owner's own agency profile - name/owner/email are set at "
        + "onboarding and are read-only here; gstNumber/address/currency/defaultCommission/logoKey "
        + "are the fields this endpoint can change.")
public class AgencyProfileResponse {

    @Schema(example = "Global Explorer Travels")
    private String agencyName;

    @Schema(example = "John Davis")
    private String ownerName;

    @Schema(example = "owner@globalexplorer.com")
    private String ownerEmail;

    @Schema(example = "27AABCU9603R1ZM")
    private String gstNumber;

    @Schema(example = "42 MG Road, Bengaluru")
    private String address;

    @Schema(example = "₹ (INR)")
    private String currency;

    @Schema(description = "Default commission % applied to a newly created agent", example = "2.00")
    private BigDecimal defaultCommission;

    @Schema(description = "Opaque storage key from FileStorageService, never a filesystem path")
    private String logoKey;

    @Schema(description = "GST home state code - decides CGST+SGST vs IGST on invoices", example = "27")
    private String stateCode;

    @Schema(description = "Machine currency code the accounting module reads; currency above stays a display label", example = "INR")
    private String baseCurrencyCode;

    @Schema(description = "SAC code pre-filled on a new invoice line", example = "9985")
    private String defaultSacCode;

    @Schema(description = "Printed on every invoice as standard terms", example = "Payment due within 7 days of invoice date.")
    private String invoiceTerms;

    @Schema(description = "Registered legal name, printed on tax invoices instead of the trading name", example = "Global Explorer Travels Pvt Ltd")
    private String legalName;

    @Schema(description = "Printed on every customer invoice's bank block", example = "Global Explorer Travels Pvt Ltd")
    private String bankAccountName;

    @Schema(example = "005505015417")
    private String bankAccountNumber;

    @Schema(example = "ICIC0000055")
    private String bankIfscCode;

    @Schema(example = "MG Road branch, Bengaluru")
    private String bankBranch;
}
