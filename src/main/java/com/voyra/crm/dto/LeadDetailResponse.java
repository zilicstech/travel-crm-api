package com.voyra.crm.dto;

import com.voyra.crm.enums.ClientType;
import com.voyra.crm.enums.LeadPriority;
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
@Schema(description = "Full lead detail, including the traveller manifest, proposal items and "
        + "margin - none of which is ever exposed on the public proposal endpoint")
public class LeadDetailResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String clientId;

    @Schema(description = "Denormalized snapshot, live-synced on client rename", example = "Ajay Sharma")
    private String clientName;

    @Schema(example = "B2C")
    private ClientType clientType;

    @Schema(description = "Resolved from the client's primary member", example = "ajay.sharma@example.com")
    private String contactEmail;

    @Schema(description = "Resolved from the client's primary member", example = "+91")
    private String contactCountryCode;

    @Schema(description = "Resolved from the client's primary member", example = "9876543210")
    private String contactPhone;

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

    @Schema(description = "The agent (or the Owner's own id) who created this lead - the lead's owner for access control", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String createdBy;

    @Schema(description = "Denormalized snapshot, live-synced on agent rename", example = "Liam Smith")
    private String createdByName;

    @Schema(example = "2026-08-20")
    private LocalDate followUpDate;

    @Schema(description = "Required when status is LOST", example = "Booked with a competitor")
    private String lostReason;

    @Schema(description = "What the client asked for, in the agent's words",
            example = "Honeymoon package, wants a desert safari and a beach resort")
    private String leadDescription;

    @Schema(description = "Standing preferences for this trip", example = "Emirates preferred, vegetarian meals")
    private String preferences;

    @Schema(description = "One remark for the whole trip, captured on Add Lead step 4",
            example = "First time abroad, celebrating their anniversary")
    private String specialNotes;

    @Schema(description = "Trip-level standing preferences, validated against agency_setting "
            + "(kind SERVICE_PREFERENCE) - distinct from a service's own preferences")
    private List<String> travelPreferences;

    @Schema(example = "2026-08-13T09:15:22")
    private LocalDateTime createdAt;

    @Schema(example = "2026-08-14T11:02:41")
    private LocalDateTime updatedAt;

    @Schema(description = "followUpDate is in the past AND status is not BOOKED/LOST")
    private boolean overdue;

    @Schema(description = "Headcount as quoted by the client")
    private GuestDetails guestDetails;

    @Schema(description = "Named travellers. May legitimately be shorter than the headcount while "
            + "the enquiry is still forming - see manifestComplete.")
    private List<LeadMemberResponse> members;

    @Schema(description = "Every non-dropped traveller is named and carries the identity fields "
            + "this lead's categories require. A booking or visa filing should be gated on this.")
    private Boolean manifestComplete;

    @Schema(description = "This lead's service instances - any number of Flight/Hotel/Visa/Transfer")
    private List<ServiceResponse> services;

    @Schema(description = "This lead's follow-up promises")
    private List<FollowUpResponse> followUps;

    @Schema(description = "Deprecated - superseded by bookings, kept empty for one release so an older cached frontend bundle does not break")
    private List<VoucherResponse> vouchers;

    @Schema(description = "This lead's bookings, across every service - several per service are legal (e.g. two flight bookings for a round trip)")
    private List<BookingResponse> bookings;

    @Schema(description = "This lead's client invoices, whole-trip and per-service")
    private List<ClientInvoiceResponse> invoices;

    @Schema(description = "This lead's proposal line items")
    private List<ProposalItemResponse> proposalItems;

    @Schema(description = "Sum of proposalItems' netCost, excluding lines whose owning service is cancelled",
            example = "42000.00")
    private BigDecimal totalNetCost;

    @Schema(description = "Sum of proposalItems' sellingPrice, excluding lines whose owning service is cancelled",
            example = "52000.00")
    private BigDecimal totalSellingPrice;

    @Schema(description = "Server-computed margin %, based on the total net cost and selling price", example = "19.2")
    private BigDecimal marginPercent;

    @Schema(description = "This lead's agent-authored notes")
    private List<LeadNoteResponse> notes;

    @Schema(description = "Whether a public share link has been generated for this lead's proposal")
    private boolean hasPublicProposalLink;

    @Schema(description = "When true, the whole proposal is frozen - the customer's public "
            + "selection endpoint and every proposal-item write are rejected until an "
            + "agent/owner unlocks it. Auto-set true when a customer confirms their option picks.")
    private boolean proposalLocked;

    @Schema(description = "Manually flagged by an owner/agent for senior attention", example = "false")
    private Boolean escalated;

    @Schema(example = "2026-09-12T10:00:00")
    private LocalDateTime escalatedAt;

    @Schema(example = "Client threatening to cancel over a pricing dispute")
    private String escalationReason;
}
