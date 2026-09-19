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

    @Size(max = 20, message = "GSTIN must be 20 characters or fewer")
    @Schema(example = "27AABCU9603R1ZM")
    private String gstin;

    @Schema(example = "27")
    private String stateCode;

    @Size(max = 500, message = "Billing address must be 500 characters or fewer")
    @Schema(example = "42 MG Road, Bengaluru")
    private String billingAddress;

    @Schema(description = "Recipient is outside India")
    private Boolean isOverseas;
}
