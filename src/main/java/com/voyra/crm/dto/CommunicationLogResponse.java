package com.voyra.crm.dto;

import com.voyra.crm.enums.CommunicationChannel;
import com.voyra.crm.enums.CommunicationDirection;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One logged call, WhatsApp thread, email or meeting")
public class CommunicationLogResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String clientId;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String leadId;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String memberId;

    @Schema(example = "WHATSAPP")
    private CommunicationChannel channel;

    @Schema(example = "OUTBOUND")
    private CommunicationDirection direction;

    @Schema(example = "Itinerary confirmation")
    private String subject;

    @Schema(example = "Confirmed the client is happy with the Bali itinerary, asked for the invoice.")
    private String summary;

    @Schema(example = "2026-09-18T14:30:00")
    private LocalDateTime occurredAt;

    @Schema(example = "8")
    private Integer durationMinutes;

    @Schema(example = "Client will confirm by Friday")
    private String outcome;

    @Schema(description = "Whether a file (screenshot, email export) is attached", example = "true")
    private Boolean hasFile;

    @Schema(example = "whatsapp-thread.png")
    private String fileName;

    @Schema(example = "Liam Fernandes")
    private String actorName;

    @Schema(example = "2026-09-18T14:35:00")
    private LocalDateTime createdAt;
}
