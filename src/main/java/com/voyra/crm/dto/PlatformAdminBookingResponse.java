package com.voyra.crm.dto;

import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.VoyraEarnedSource;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.util.List;

/** The platform admin's view: the agency view plus Voyra's own money and the supplier event trail. Never sent to an agency. */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@Schema(description = "A platform booking with Voyra's earnings and audit trail, for platform admins")
public class PlatformAdminBookingResponse extends PlatformBookingResponse {

    @Schema(description = "Agency that owns the booking", example = "t1d2e3f4-0000-0000-0000-000000000001")
    private String tenantId;

    @Schema(description = "Agency name", example = "Acme Travel")
    private String agencyName;

    @Schema(description = "MOCK, UAT or PROD supplier environment", example = "PROD")
    private SupplierEnvironment environment;

    @Schema(description = "Published supplier fare", example = "5000.00")
    private BigDecimal gross;

    @Schema(description = "Supplier base fare", example = "4250.00")
    private BigDecimal base;

    @Schema(description = "Discount percent off gross given to the agency", example = "10.00")
    private BigDecimal discountPct;

    @Schema(description = "What Voyra earns on the booking", example = "250.00")
    private BigDecimal voyraEarned;

    @Schema(description = "ESTIMATE until the supplier reports the real figure", example = "ESTIMATE")
    private VoyraEarnedSource voyraEarnedSource;

    @Schema(description = "What Voyra actually earned per the Tripjack statement; voyraEarned stays the estimate", example = "262.00")
    private BigDecimal voyraEarnedActual;

    @Schema(description = "Commission credited to the agency before TDS", example = "100.00")
    private BigDecimal agencyCommission;

    @Schema(description = "TDS deducted from the agency commission", example = "5.00")
    private BigDecimal tds;

    @Schema(description = "Voyra's net after agency commission and TDS", example = "145.00")
    private BigDecimal voyraNet;

    @Schema(description = "Every audited event, oldest first")
    private List<PlatformBookingEventResponse> events;
}
