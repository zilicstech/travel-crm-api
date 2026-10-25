package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Reject a bank top-up with a reason")
public class TopupRejectRequest {

    @NotBlank
    @Size(min = 3, max = 1000)
    @Schema(description = "Why the top-up is rejected; shown to the agency", example = "Transfer not found in the bank statement")
    private String note;
}
