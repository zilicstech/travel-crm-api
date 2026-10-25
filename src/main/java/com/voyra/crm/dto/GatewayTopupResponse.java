package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "Everything the browser needs to open the gateway checkout for a new top-up")
public class GatewayTopupResponse {

    @Schema(description = "The PENDING top-up request id", example = "a1b2c3d4")
    private String topupId;

    @Schema(description = "Gateway order id to pass to checkout", example = "order_N5vY1c2k3")
    private String gatewayOrderId;

    @Schema(description = "Publishable gateway key id (never the secret); 'stub' when no gateway is configured", example = "rzp_test_abc123")
    private String keyId;

    @Schema(description = "Amount in paise, as checkout expects", example = "5000000")
    private Long amountPaise;

    @Schema(description = "razorpay or stub; with stub, pay through the simulate endpoint", example = "razorpay")
    private String provider;

    @Schema(description = "Agency name to show on the checkout", example = "Sky Trips")
    private String agencyName;
}
