package com.voyra.crm.dto;

import com.voyra.crm.enums.ServiceType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** Patch semantics: a null field is left unchanged. */
@Data
@Schema(description = "Request body for editing a vendor - null fields are left unchanged")
public class VendorUpdateRequest {

    @Size(max = 150, message = "Vendor name must be at most 150 characters")
    @Schema(example = "Tripjack")
    private String name;

    @Schema(example = "[\"FLIGHT\", \"HOTEL\"]")
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

    @Schema(example = "true")
    private Boolean isActive;

    @Schema(example = "2")
    private Integer sortOrder;
}
