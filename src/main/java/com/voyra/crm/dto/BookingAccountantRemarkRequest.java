package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "The accountant's own note on a booking; blank clears it")
public class BookingAccountantRemarkRequest {

    @Size(max = 255, message = "The remark must be 255 characters or fewer")
    @Schema(example = "Done (Zohaib)")
    private String remark;
}
