package com.voyra.crm.controller;

import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.service.GatewayWebhookService;
import com.voyra.crm.service.GatewayWebhookService.WebhookOutcome;
import com.voyra.crm.service.payments.PaymentGateway;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Razorpay webhook (the /api/public/** permitAll surface): trusted only through its HMAC signature over the raw body. */
@RestController
@RequestMapping("/api/public/payments/razorpay")
@RequiredArgsConstructor
@Tag(name = "Public - Payment webhooks", description = "Signed gateway callbacks; no user auth")
public class PublicPaymentWebhookController {

    private final GatewayWebhookService webhookService;
    private final PaymentGateway gateway;

    @PostMapping("/webhook")
    @Operation(summary = "Razorpay webhook", description = "Body is verified byte for byte against X-Razorpay-Signature.")
    @ApiResponse(responseCode = "400", description = "Bad signature; nothing stored")
    @ApiResponse(responseCode = "503", description = "Order not known yet; the gateway retries")
    public ResponseEntity<Void> webhook(@RequestBody String rawBody,
                                        @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {
        // The stub accepts any signature (for simulate), so it must never be reachable from outside.
        if ("stub".equals(gateway.name())) {
            throw new NotFoundException("No payment gateway is configured");
        }
        WebhookOutcome outcome = webhookService.handle(rawBody, signature);
        return ResponseEntity.status(outcome == WebhookOutcome.RETRY ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK).build();
    }
}
