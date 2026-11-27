package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Bookings Accounts marks verified (without invoicing them), or un-marks")
public class BookingVerificationRequest {

    @NotEmpty(message = "Tick at least one booking")
    @Size(max = 200, message = "Verify at most 200 bookings at a time")
    @Schema(example = "[\"b1\", \"b2\"]")
    private List<String> bookingIds;

    @Schema(description = "true marks them verified, false undoes it", example = "true")
    private boolean verified;
}
