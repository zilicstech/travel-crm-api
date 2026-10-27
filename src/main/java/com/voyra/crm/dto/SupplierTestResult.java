package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Outcome of a live test search against a supplier with the platform credential")
public class SupplierTestResult {

    @Schema(description = "Whether the supplier answered the test search", example = "true")
    private boolean ok;

    @Schema(description = "What happened - the supplier's error on failure; never the key", example = "Tripjack answered with 42 offers")
    private String message;

    @Schema(description = "How long the call took", example = "812")
    private long millis;
}
