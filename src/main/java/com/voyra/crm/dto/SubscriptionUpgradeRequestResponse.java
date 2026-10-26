package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
@Schema(description = "The upgrade request reached Voyra")
public class SubscriptionUpgradeRequestResponse {

    @Schema(example = "true")
    private boolean requested;
}
