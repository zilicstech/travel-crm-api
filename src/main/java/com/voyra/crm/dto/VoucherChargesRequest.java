package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "The accountant's fare breakdown of a voucher and the commission its supplier pays on it. The breakdown "
        + "must add up to the voucher's purchase price; sending none clears it.")
public class VoucherChargesRequest {

    @Schema(description = "The fare's charges in the purchase currency - keys basic, yq, yr, k3, oc, otherTax, processing, "
            + "otherCharges. They must add up to the voucher's purchase price", example = "{\"basic\": 8200, \"yq\": 1400, \"k3\": 410}")
    private java.util.Map<String, java.math.BigDecimal> charges;

    @Schema(description = "Flights: the commission the supplier pays the agency - PERCENT of the basic fare, or FLAT in the purchase currency", example = "PERCENT")
    private com.voyra.crm.enums.MarkupMode commissionMode;

    @Schema(description = "The commission % or amount", example = "5.00")
    private java.math.BigDecimal commissionValue;

    @Schema(description = "TDS % the supplier deducts on the commission (sec. 194H); 2 when not given", example = "2.00")
    private java.math.BigDecimal supplierTdsRate;
}
