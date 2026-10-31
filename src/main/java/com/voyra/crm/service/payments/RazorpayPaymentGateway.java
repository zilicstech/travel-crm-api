package com.voyra.crm.service.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.voyra.crm.exception.SupplierException;
import com.voyra.crm.models.GatewayOrderCreated;
import com.voyra.crm.models.GatewayPayment;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.voyra.crm.util.ExcludeFromJacocoGenerated;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Razorpay over plain REST (no SDK): Basic auth with key id and secret; webhooks signed with HMAC-SHA256. Never logs keys or payloads. */
public class RazorpayPaymentGateway implements PaymentGateway {

    private final RestClient client;
    private final String keyId;
    private final byte[] webhookSecret;

    public RazorpayPaymentGateway(RestClient.Builder builder, String keyId, String keySecret, String webhookSecret, String baseUrl) {
        if (!StringUtils.hasText(keyId) || !StringUtils.hasText(keySecret) || !StringUtils.hasText(webhookSecret)) {
            throw new IllegalStateException("app.payments.provider=razorpay needs RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET and RAZORPAY_WEBHOOK_SECRET");
        }
        this.keyId = keyId;
        this.webhookSecret = webhookSecret.getBytes(StandardCharsets.UTF_8);
        this.client = builder.baseUrl(baseUrl).defaultHeaders(h -> h.setBasicAuth(keyId, keySecret)).build();
    }

    public static long toPaise(BigDecimal amountInr) {
        return amountInr.movePointRight(2).longValueExact();
    }

    @Override
    public GatewayOrderCreated createOrder(BigDecimal amountInr, String receipt, Map<String, String> notes) {
        JsonNode body = call(() -> client.post().uri("/v1/orders").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("amount", toPaise(amountInr), "currency", "INR", "receipt", receipt, "notes", notes))
                .retrieve().body(JsonNode.class));
        return new GatewayOrderCreated(body.path("id").asText(), body.path("amount").asLong(), body.path("currency").asText());
    }

    @Override
    public List<GatewayPayment> fetchOrderPayments(String gatewayOrderId) {
        JsonNode body = call(() -> client.get().uri("/v1/orders/{id}/payments", gatewayOrderId).retrieve().body(JsonNode.class));
        List<GatewayPayment> payments = new ArrayList<>();
        for (JsonNode p : body.path("items")) {
            payments.add(new GatewayPayment(p.path("id").asText(), p.path("order_id").asText(),
                    BigDecimal.valueOf(p.path("amount").asLong(), 2), p.path("currency").asText(), p.path("status").asText()));
        }
        return payments;
    }

    @Override
    public boolean verifyWebhook(String rawBody, String signature) {
        if (rawBody == null || signature == null) {
            return false;
        }
        Mac mac = hmacSha256(new SecretKeySpec(webhookSecret, "HmacSHA256"));
        byte[] expected = HexFormat.of().formatHex(mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8)))
                .getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, signature.trim().toLowerCase().getBytes(StandardCharsets.US_ASCII));
    }

    /** Every JRE ships HmacSHA256 and accepts any HmacSHA256 key spec, so the catch the compiler forces cannot run. */
    @ExcludeFromJacocoGenerated
    private static Mac hmacSha256(SecretKeySpec key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    @Override
    public String publicKeyId() {
        return keyId;
    }

    @Override
    public String name() {
        return "razorpay";
    }

    private static JsonNode call(java.util.function.Supplier<JsonNode> request) {
        try {
            JsonNode body = request.get();
            if (body == null) {
                throw new SupplierException("Razorpay returned an empty response", null);
            }
            return body;
        } catch (RestClientException e) {
            // Status only - the exception message can echo the response body.
            throw new SupplierException("Razorpay call failed: " + e.getClass().getSimpleName(), null);
        }
    }

    @Override
    public boolean simulateEnabled() {
        return false;
    }
}
