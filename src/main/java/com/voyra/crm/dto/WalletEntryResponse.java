package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@Schema(description = "One posting on a wallet statement")
public class WalletEntryResponse {

    @Schema(description = "Entry id", example = "E1A2B3C4")
    private String id;

    @Schema(description = "Posting type", example = "TOPUP")
    private String type;

    @Schema(description = "Signed amount; HOLD and HOLD_RELEASE move only the held figure", example = "5000.00")
    private BigDecimal amount;

    @Schema(description = "Balance after this posting", example = "12000.00")
    private BigDecimal balanceAfter;

    @Schema(description = "Held after this posting", example = "2000.00")
    private BigDecimal heldAfter;

    @Schema(description = "Balance minus held after this posting", example = "10000.00")
    private BigDecimal availableAfter;

    @Schema(description = "Narration", example = "Top-up by bank transfer")
    private String narration;

    @Schema(description = "What the entry refers to", example = "BOOKING")
    private String refType;

    @Schema(description = "Id of the referenced record", example = "B1A2B3C4")
    private String refId;

    @Schema(description = "Who posted it", example = "Asha Rao")
    private String createdByName;

    @Schema(description = "When it was posted", example = "2026-10-25T10:00:00Z")
    private Instant createdAt;
}
