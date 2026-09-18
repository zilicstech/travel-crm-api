package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** Pre-save advisory check - both fields optional, a caller sends whatever it has typed so far. */
@Data
@Schema(description = "Request body for a pre-save duplicate check")
public class ClientDuplicateCheckRequest {

    @Schema(example = "9876543210")
    private String identifier;

    @Schema(example = "Ajay Sharma")
    private String name;
}
