package com.voyra.crm.dto;

import com.voyra.crm.enums.LeadCategory;
import com.voyra.crm.enums.LeadPriority;
import com.voyra.crm.enums.LeadSource;
import com.voyra.crm.enums.LeadStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Full Lead detail, including proposal items and margin - never exposed to the public proposal endpoint")
public class LeadDetailResponse {

    @Schema(example = "L5N9P3")
    private String id;

    @Schema(example = "K3M8P1")
    private String customerId;

    @Schema(example = "Jane Doe")
    private String name;

    @Schema(example = "jane.doe@example.com")
    private String email;

    @Schema(example = "+91")
    private String countryCode;

    @Schema(example = "9876543210")
    private String phone;

    @Schema(example = "Dubai, UAE")
    private String destination;

    @Schema(example = "2026-09-15")
    private LocalDate travelDateFrom;

    @Schema(example = "2026-09-22")
    private LocalDate travelDateTo;

    @Schema(description = "Multi-select, stored as a native array")
    private List<LeadCategory> categories;

    @Schema(example = "₹1,50,000 - ₹2,00,000")
    private String budget;

    @Schema(example = "PROPOSAL_SENT")
    private LeadStatus status;

    @Schema(example = "WEBSITE")
    private LeadSource source;

    @Schema(example = "HIGH")
    private LeadPriority priority;

    @Schema(example = "CB9Y0N")
    private String assignedTo;

    @Schema(description = "Denormalized snapshot, live-synced on agent rename", example = "Liam Smith")
    private String assignedAgentName;

    @Schema(example = "2026-08-20")
    private LocalDate followUpDate;

    @Schema(description = "Required when status is LOST")
    private String lostReason;

    @Schema(example = "2026-08-13T09:15:22")
    private LocalDateTime createdDate;

    @Schema(description = "followUpDate is in the past AND status is not BOOKED/LOST")
    private boolean overdue;

    private GuestDetails guestDetails;

    @Schema(description = "Only present when VISA is in categories")
    private VisaTrackerResponse visaTracker;

    private List<ProposalItemResponse> proposalItems;

    @Schema(description = "Sum of proposalItems' netCost", example = "42000.00")
    private BigDecimal totalNetCost;

    @Schema(description = "Sum of proposalItems' sellingPrice", example = "52000.00")
    private BigDecimal totalSellingPrice;

    @Schema(description = "Server-computed margin %, based on the total net cost and selling price", example = "19.2")
    private BigDecimal marginPercent;

    private List<LeadNoteResponse> notes;

    @Schema(description = "Whether a public share link has been generated for this lead's proposal")
    private boolean hasPublicProposalLink;
}
