package com.voyra.crm.dto;

import com.voyra.crm.enums.CancelReason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "An agency's cancellation request: what to cancel and why (sent as the multipart part 'request')")
public record CancelRequestCreateRequest(
        @Schema(description = "Why the booking is to be cancelled", example = "TRAVEL_PLAN_CANCELLED")
        @NotNull(message = "Choose a cancellation reason") CancelReason reason,
        @Schema(description = "Flight: positions of the passengers to cancel in the booking's passenger list; ignored for a hotel", example = "[0]")
        List<Integer> passengers,
        @Schema(description = "Flight: sector keys to cancel (d0 onward, d1 return); empty means every sector; ignored for a hotel", example = "[\"d0\"]")
        List<String> sectors,
        @Schema(description = "The agency's own words; required for a reason in the Other group", example = "Passenger hospitalised")
        @Size(max = 2000, message = "Remarks can be at most 2000 characters") String remarks) {
}
