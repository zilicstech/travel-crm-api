package com.voyra.crm.dto;

import com.voyra.crm.enums.ClientType;
import com.voyra.crm.enums.LeadPriority;
import com.voyra.crm.enums.LeadStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A lead's list-view summary. Also the kanban card: group by status for a "
        + "board, order by createdAt for a list - both views read this same payload.")
public class LeadResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String clientId;

    @Schema(description = "Denormalized snapshot, live-synced on client rename", example = "Ajay Sharma")
    private String clientName;

    @Schema(description = "Denormalized snapshot of the client's type", example = "B2C")
    private ClientType clientType;

    @Schema(example = "Dubai, UAE")
    private String destination;

    @Schema(example = "2026-09-15")
    private LocalDate travelDateFrom;

    @Schema(example = "2026-09-22")
    private LocalDate travelDateTo;

    @Schema(description = "Multi-select, stored as a native array")
    private List<String> categories;

    @Schema(example = "1,50,000 - 2,00,000")
    private String budget;

    @Schema(example = "PROPOSAL_SENT")
    private LeadStatus status;

    @Schema(example = "WEBSITE")
    private String source;

    @Schema(example = "HIGH")
    private LeadPriority priority;

    @Schema(description = "Server-computed as adults + kids", example = "4")
    private Integer totalTravellers;

    @Schema(description = "Travellers on the manifest whose status is CONFIRMED", example = "3")
    private Integer confirmedTravellers;

    @Schema(description = "The agent (or the Owner's own id) who created this lead - the lead's owner for access control", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String createdBy;

    @Schema(description = "Denormalized snapshot, live-synced on agent rename", example = "Liam Smith")
    private String createdByName;

    @Schema(example = "2026-08-20")
    private LocalDate followUpDate;

    @Schema(example = "2026-08-13T09:15:22")
    private LocalDateTime createdAt;

    @Schema(description = "followUpDate is in the past AND status is not BOOKED/LOST")
    private boolean overdue;
}
