package com.voyra.crm.dto;

import com.voyra.crm.enums.ServiceType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A supplier master record")
public class VendorResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "Tripjack")
    private String name;

    @Schema(example = "[\"FLIGHT\", \"HOTEL\"]")
    private List<ServiceType> serviceTypes;

    @Schema(example = "Rohan Mehta")
    private String contactPerson;

    @Schema(example = "+91 98765 43210")
    private String phone;

    @Schema(example = "bookings@tripjack.com")
    private String email;

    @Schema(example = "12th Floor, DLF Cyber City, Gurugram")
    private String address;

    @Schema(example = "07AAACT2727Q1ZW")
    private String gstNumber;

    @Schema(example = "Preferred consolidator for domestic flights")
    private String notes;

    @Schema(example = "8% commission, net 15")
    private String defaultRateNote;

    @Schema(example = "true")
    private Boolean isActive;

    @Schema(example = "0")
    private Integer sortOrder;

    @Schema(example = "2026-01-10T09:00:00")
    private LocalDateTime createdAt;

    @Schema(example = "2026-03-02T14:20:00")
    private LocalDateTime updatedAt;

    // --- Accounts-payable profile. Bank/TDS/PAN/credit-limit fields are omitted (null) when
    // returned to an AGENT - see VendorService#toResponse. ---

    @Schema(example = "07")
    private String stateCode;

    @Schema(example = "AAACT2727Q")
    private String panNumber;

    @Schema(example = "false")
    private Boolean isPrepaid;

    @Schema(example = "15")
    private Integer paymentTermsDays;

    @Schema(example = "500000.00")
    private BigDecimal creditLimitInr;

    @Schema(example = "150000.00")
    private BigDecimal lowBalanceThresholdInr;

    @Schema(example = "194C")
    private String tdsSection;

    @Schema(example = "2.000")
    private BigDecimal tdsRatePercent;

    @Schema(example = "Tripjack Travels Pvt Ltd")
    private String bankAccountName;

    @Schema(example = "000123456789")
    private String bankAccountNumber;

    @Schema(example = "HDFC0000123")
    private String bankIfsc;
}
