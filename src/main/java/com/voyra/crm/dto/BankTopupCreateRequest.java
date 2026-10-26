package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;

@Data
@Schema(description = "A bank-transfer top-up: the amount sent, its UTR and the payment proof (multipart form)")
public class BankTopupCreateRequest {

    @NotNull
    @DecimalMin("1")
    @DecimalMax(value = "100000000", message = "A top-up can be at most ₹10,00,00,000")
    @Digits(integer = 17, fraction = 2, message = "Amount can have at most 2 decimal places") // the cap above names the size
    @Schema(description = "Amount transferred, 1 to 10,00,00,000", example = "50000.00")
    private BigDecimal amount;

    @NotBlank
    @Size(min = 4, max = 40)
    @Pattern(regexp = "[A-Za-z0-9 ]*", message = "Use letters and digits only")
    @Schema(description = "Bank reference (UTR); unique across the platform, ignoring case and spaces", example = "SBIN4521879034")
    private String bankRef;

    @Size(max = 500)
    @Schema(description = "Optional note for the platform desk", example = "NEFT from HDFC current account", nullable = true)
    private String note;

    @NotNull(message = "Attach the payment proof")
    @Schema(description = "Proof of transfer: PDF, JPG, PNG or WEBP up to 5 MB", type = "string", format = "binary")
    private MultipartFile proof;
}
