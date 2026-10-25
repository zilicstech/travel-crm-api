package com.voyra.crm.dto;

import com.voyra.crm.enums.PlatformBookingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "Settle a booking whose supplier outcome is unknown")
public class PlatformBookingResolveRequest {

    @NotNull
    @Schema(description = "TICKETED or FAILED", example = "TICKETED")
    private PlatformBookingStatus outcome;

    @Size(max = 40)
    @Schema(description = "Airline reference; required when TICKETED", example = "ABC123")
    private String pnr;

    @Schema(description = "Ticket numbers, when known")
    private List<String> ticketNumbers;

    @NotBlank
    @Size(max = 500)
    @Schema(description = "Why, for the audit log", example = "Seen as ticketed on the supplier portal")
    private String note;
}
