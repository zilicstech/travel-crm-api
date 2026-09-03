package com.voyra.crm.dto;

import com.voyra.crm.enums.LeadMemberStatus;
import com.voyra.crm.enums.MemberRelation;
import com.voyra.crm.enums.PaxType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One traveller on a lead's manifest, with the identity fields a booking or "
        + "visa filing needs and this traveller's own document checklist")
public class LeadMemberResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String memberId;

    @Schema(example = "Ankita Sharma")
    private String memberName;

    @Schema(example = "SPOUSE")
    private MemberRelation relation;

    @Schema(example = "CONFIRMED")
    private LeadMemberStatus status;

    @Schema(description = "Set when status is DROPPED", example = "Visa rejected, travelling separately")
    private String droppedReason;

    @Schema(example = "1991-11-03")
    private LocalDate dob;

    @Schema(example = "Female")
    private String gender;

    @Schema(example = "Indian")
    private String nationality;

    @Schema(example = "M7654321")
    private String passportNumber;

    @Schema(example = "2031-08-14")
    private LocalDate passportExpiry;

    @Schema(description = "Fare class at this lead's departure date, derived from date of birth. "
            + "UNKNOWN when either the date of birth or the travel date is still missing.",
            example = "ADULT")
    private PaxType paxType;

    @Schema(description = "Age at this lead's departure date, not today", example = "34")
    private Integer ageAtTravel;

    @Schema(description = "Date of birth falls inside the trip, so the outbound and inbound legs price differently")
    private Boolean crossesPaxBoundary;

    @Schema(description = "Server-computed: every identity field this lead's categories require is present. "
            + "FLIGHT needs name, date of birth and gender; VISA additionally needs passport number, "
            + "expiry and nationality; HOTEL needs only a name.")
    private Boolean documentsComplete;

    @Schema(example = "2026-08-13T09:15:22")
    private LocalDateTime createdAt;
}
