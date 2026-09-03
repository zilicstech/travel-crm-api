package com.voyra.crm.dto;

import com.voyra.crm.enums.ClientType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Patch semantics: a null field means 'not supplied' and is left unchanged. "
        + "Renaming the client re-syncs the denormalized client_name snapshot on every lead, "
        + "booking, invoice and visa case in the same transaction.")
public class ClientUpdateRequest {

    @Size(max = 150, message = "Identifier must be 150 characters or fewer")
    @Schema(example = "9876543210")
    private String identifier;

    @Size(max = 150, message = "Name must be 150 characters or fewer")
    @Schema(example = "Ajay Sharma")
    private String name;

    @Schema(example = "B2B")
    private ClientType type;
}
