package com.voyra.crm.dto;

import com.voyra.crm.enums.ClientType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "An existing client that might be the same person/company as the one being created")
public class ClientDuplicateCandidateResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "Ajay Sharma")
    private String name;

    @Schema(example = "9876543210")
    private String identifier;

    @Schema(example = "B2C")
    private ClientType type;

    @Schema(description = "EXACT_IDENTIFIER, PHONE_SUFFIX or NAME_SIMILARITY", example = "PHONE_SUFFIX")
    private String matchReason;
}
