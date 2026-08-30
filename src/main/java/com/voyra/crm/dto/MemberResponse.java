package com.voyra.crm.dto;

import com.voyra.crm.enums.MemberRelation;
import com.voyra.crm.enums.MemberType;
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
@Schema(description = "A person on a client's roster")
public class MemberResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String memberId;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String clientId;

    @Schema(example = "Ankita Sharma")
    private String name;

    @Schema(description = "CLIENT for the primary member, MEMBER for everyone else", example = "MEMBER")
    private MemberType type;

    @Schema(example = "SPOUSE")
    private MemberRelation relation;

    @Schema(example = "ankita.sharma@example.com")
    private String email;

    @Schema(example = "+91")
    private String countryCode;

    @Schema(example = "9876543211")
    private String phone;

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

    @Schema(description = "Completed years as of today. Derived, never stored - see paxType for the fare-relevant value.",
            example = "34")
    private Integer currentAge;

    @Schema(description = "Passport is missing or expires within six months of today")
    private Boolean passportActionNeeded;

    @Schema(description = "Uploaded passport, visa and identity documents")
    private List<DocumentResponse> documents;

    @Schema(description = "False once the member is deactivated")
    private Boolean isActive;

    @Schema(example = "2026-08-13T09:15:22")
    private LocalDateTime createdAt;

    @Schema(example = "2026-08-14T11:02:41")
    private LocalDateTime modifiedAt;
}
