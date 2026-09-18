package com.voyra.crm.dto;

import com.voyra.crm.enums.CommunicationChannel;
import com.voyra.crm.enums.CommunicationDirection;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "Request body for logging a call, WhatsApp thread, email or meeting")
public class CommunicationLogCreateRequest {

    @Schema(description = "Set only when this was about one specific enquiry", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String leadId;

    @Schema(description = "Which traveller on the roster this was with, if not the primary contact", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String memberId;

    @NotNull(message = "Channel is required")
    @Schema(example = "WHATSAPP")
    private CommunicationChannel channel;

    @NotNull(message = "Direction is required")
    @Schema(example = "OUTBOUND")
    private CommunicationDirection direction;

    @Size(max = 200, message = "Subject must be 200 characters or fewer")
    @Schema(example = "Itinerary confirmation")
    private String subject;

    @NotBlank(message = "Summary is required")
    @Size(max = 2000, message = "Summary must be 2000 characters or fewer")
    @Schema(example = "Confirmed the client is happy with the Bali itinerary, asked for the invoice.")
    private String summary;

    @Schema(description = "Defaults to now if omitted", example = "2026-09-18T14:30:00")
    private LocalDateTime occurredAt;

    @Schema(example = "8")
    private Integer durationMinutes;

    @Size(max = 200, message = "Outcome must be 200 characters or fewer")
    @Schema(example = "Client will confirm by Friday")
    private String outcome;
}
