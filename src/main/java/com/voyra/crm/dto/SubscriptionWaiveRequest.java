package com.voyra.crm.dto;

import com.voyra.crm.enums.WaivedReason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.Instant;

@Data
@Schema(description = "Waive an agency's subscription fee")
public class SubscriptionWaiveRequest {

    @NotNull(message = "reason is required")
    @Schema(description = "Why the fee is waived", example = "COMPLIMENTARY")
    private WaivedReason reason;

    @Schema(description = "When the waiver ends; omit for no expiry", example = "2027-01-31T00:00:00Z", nullable = true)
    private Instant until;
}
