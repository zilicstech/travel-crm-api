package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Agency summary/detail")
public class AgencyResponse {

    private String id;
    private String agencyName;
    private String ownerName;
    private String ownerEmail;
    private Boolean isActive;
    private Long agentsCount;

    @Schema(description = "Only populated on the single-agency detail endpoint (cross-tenant aggregate read)")
    private BigDecimal totalRevenue;

    private LocalDateTime createdDate;
}
