package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@Schema(description = "Money an agency owes Voyra after a shortfall")
public class ReceivableResponse {

    @Schema(description = "Receivable id", example = "R1A2B3C4")
    private String id;

    @Schema(description = "Amount still due", example = "1200.00")
    private BigDecimal amount;

    @Schema(description = "Amount when it was opened", example = "1500.00")
    private BigDecimal originalAmount;

    @Schema(description = "Why it was opened", example = "LATE_TICKET")
    private String reason;

    @Schema(description = "The reason in plain words, for screens", example = "commission taken back on a cancelled booking")
    private String reasonText;

    @Schema(description = "When it was opened", example = "2026-10-25T10:00:00Z")
    private Instant createdAt;
}
