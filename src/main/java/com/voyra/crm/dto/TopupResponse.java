package com.voyra.crm.dto;

import com.voyra.crm.enums.TopupMode;
import com.voyra.crm.enums.TopupStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@Schema(description = "A wallet top-up request")
public class TopupResponse {

    @Schema(description = "Top-up id", example = "a1b2c3d4")
    private String id;

    @Schema(description = "BANK_TRANSFER or GATEWAY")
    private TopupMode mode;

    @Schema(description = "Amount requested", example = "50000.00")
    private BigDecimal amount;

    @Schema(description = "Amount actually credited once approved", example = "49500.00", nullable = true)
    private BigDecimal approvedAmount;

    @Schema(description = "PENDING, APPROVED, REJECTED, FAILED or EXPIRED")
    private TopupStatus status;

    @Schema(description = "Bank reference (UTR) as typed", example = "SBIN4521879034", nullable = true)
    private String bankRef;

    @Schema(description = "Gateway order id for GATEWAY top-ups", example = "order_Nx1", nullable = true)
    private String gatewayOrderId;

    @Schema(description = "Agency's note", example = "NEFT from HDFC", nullable = true)
    private String note;

    @Schema(description = "Platform desk's note on the decision", example = "Bank charges deducted", nullable = true)
    private String reviewNote;

    @Schema(description = "Who requested it", example = "Asha Rao", nullable = true)
    private String requestedByName;

    @Schema(description = "When it was requested")
    private Instant createdAt;

    @Schema(description = "When it was decided", nullable = true)
    private Instant reviewedAt;

    @Schema(description = "Agency id (platform view only)", example = "T1", nullable = true)
    private String tenantId;

    @Schema(description = "Agency name (platform view only)", example = "Sunrise Travels", nullable = true)
    private String agencyName;
}
