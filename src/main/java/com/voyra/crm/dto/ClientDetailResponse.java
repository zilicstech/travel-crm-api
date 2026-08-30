package com.voyra.crm.dto;

import com.voyra.crm.enums.ClientType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Full client detail with its member roster - the picker the Add Lead wizard reads from")
public class ClientDetailResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "9876543210")
    private String identifier;

    @Schema(example = "Ajay Sharma")
    private String name;

    @Schema(example = "B2C")
    private ClientType type;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String agentId;

    @Schema(description = "Denormalized snapshot, live-synced on agent rename", example = "Liam Smith")
    private String agentName;

    @Schema(description = "False once the client is deactivated")
    private Boolean isActive;

    @Schema(description = "Active members, primary member first")
    private List<MemberResponse> members;

    @Schema(example = "2026-08-13T09:15:22")
    private LocalDateTime createdAt;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String createdBy;

    @Schema(example = "2026-08-14T11:02:41")
    private LocalDateTime modifiedAt;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String modifiedBy;
}
