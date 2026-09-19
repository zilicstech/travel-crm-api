package com.voyra.crm.dto;

import com.voyra.crm.enums.ClientType;
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
@Schema(description = "A client's list-view summary")
public class ClientResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(description = "Phone for a B2C client, group or company handle for a B2B client", example = "9876543210")
    private String identifier;

    @Schema(example = "Ajay Sharma")
    private String name;

    @Schema(example = "B2C")
    private ClientType type;

    @Schema(description = "Number of active members on this client's roster", example = "4")
    private Integer memberCount;

    @Schema(description = "False once the client is deactivated; the identifier is then free to reuse")
    private Boolean isActive;

    @Schema(example = "2026-08-13T09:15:22")
    private LocalDateTime createdAt;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String createdBy;

    @Schema(example = "2026-08-14T11:02:41")
    private LocalDateTime modifiedAt;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String modifiedBy;

    @Schema(description = "Set when this client was created despite a duplicate-check warning", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String possibleDuplicateOf;

    @Schema(example = "27AABCU9603R1ZM")
    private String gstin;

    @Schema(example = "27")
    private String stateCode;

    @Schema(example = "42 MG Road, Bengaluru")
    private String billingAddress;

    @Schema(description = "Recipient is outside India")
    private Boolean isOverseas;
}
