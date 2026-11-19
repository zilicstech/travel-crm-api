package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;

@Data
@Schema(description = "An add-money request (multipart form): a bank transfer with its UTR and payment proof, or, with no UTR, "
        + "an offline payment described by a note")
public class BankTopupCreateRequest {

    @NotNull
    @DecimalMin("1")
    @DecimalMax(value = "100000000", message = "You can add at most ₹10,00,00,000 at a time")
    @Digits(integer = 17, fraction = 2, message = "Amount can have at most 2 decimal places") // the cap above names the size
    @Schema(description = "Amount transferred, 1 to 10,00,00,000", example = "50000.00")
    private BigDecimal amount;

    @Size(max = 40)
    @Pattern(regexp = "[A-Za-z0-9 ]*", message = "Use letters and digits only")
    @Schema(description = "Bank reference (UTR) of a bank transfer, unique across the platform ignoring case and spaces; "
            + "absent for an offline payment", example = "SBIN4521879034", nullable = true)
    private String bankRef;

    @Size(max = 500, message = "Write at most 500 characters")
    @Schema(description = "How and when the agency paid: required (3 or more characters) for an offline payment, optional with a UTR",
            example = "Cash at the Pune office on 19 Nov", nullable = true)
    private String note;

    @Schema(description = "Proof of payment (PDF, JPG, PNG or WEBP up to 5 MB): required with a UTR, optional offline",
            type = "string", format = "binary", nullable = true)
    private MultipartFile proof;
}
