package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "Add several option lines to a service's proposal in one round trip - "
        + "typically the result of a supplier search, all offered as alternatives to each other")
public class ProposalItemBatchCreateRequest {

    @Schema(description = "Ties every created line to this service instance", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String serviceId;

    @Schema(description = "Groups the created lines as mutually-exclusive alternatives. "
            + "Defaults to serviceId when omitted, which makes \"one choice per service\" the default behaviour.",
            example = "svc-flight-1")
    private String optionGroup;

    @NotEmpty(message = "At least one line is required")
    @Valid
    @Schema(description = "The option lines to create")
    private List<ProposalItemBatchLine> items;
}
