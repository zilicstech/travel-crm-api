package com.voyra.crm.service.payments;

import com.voyra.crm.models.GatewayOrderCreated;
import com.voyra.crm.models.GatewayPayment;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RazorpayPaymentGatewayTest {

    private static final String BASIC = "Basic " + Base64.getEncoder()
            .encodeToString("rzp_test_key:key_secret".getBytes(StandardCharsets.UTF_8));

    private static RazorpayPaymentGateway gateway(RestClient.Builder builder) {
        return new RazorpayPaymentGateway(builder, "rzp_test_key", "key_secret", "whsec_test", "https://api.razorpay.com");
    }

    @Test
    void createOrderSendsPaiseAndBasicAuth() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.razorpay.com/v1/orders"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", BASIC))
                .andExpect(jsonPath("$.amount").value(50050))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.receipt").value("R1"))
                .andExpect(jsonPath("$.notes.topupId").value("R1"))
                .andRespond(withSuccess("{\"id\":\"order_ABC\",\"amount\":50050,\"currency\":\"INR\",\"status\":\"created\"}",
                        MediaType.APPLICATION_JSON));

        GatewayOrderCreated created = gateway(builder).createOrder(new BigDecimal("500.50"), "R1", Map.of("topupId", "R1"));

        assertThat(created.orderId()).isEqualTo("order_ABC");
        assertThat(created.amountPaise()).isEqualTo(50050L);
        server.verify();
    }

    @Test
    void fetchOrderPaymentsConvertsPaiseToRupees() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.razorpay.com/v1/orders/order_ABC/payments"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", BASIC))
                .andRespond(withSuccess("{\"items\":[{\"id\":\"pay_1\",\"order_id\":\"order_ABC\",\"amount\":50050,"
                        + "\"currency\":\"INR\",\"status\":\"captured\"}]}", MediaType.APPLICATION_JSON));

        List<GatewayPayment> payments = gateway(builder).fetchOrderPayments("order_ABC");

        assertThat(payments).containsExactly(new GatewayPayment("pay_1", "order_ABC", new BigDecimal("500.50"), "INR", "captured"));
        server.verify();
    }

    @Test
    void verifyWebhookAcceptsTheRazorpaySignatureAndRejectsATamperedBody() throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("whsec_test".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = HexFormat.of().formatHex(mac.doFinal("{\"a\":1}".getBytes(StandardCharsets.UTF_8)));
        RazorpayPaymentGateway gateway = gateway(RestClient.builder());

        assertThat(gateway.verifyWebhook("{\"a\":1}", expected)).isTrue();
        assertThat(gateway.verifyWebhook("{\"a\":2}", expected)).isFalse();
        assertThat(gateway.verifyWebhook("{\"a\":1}", null)).isFalse();
        assertThat(gateway.verifyWebhook("{\"a\":1}", "not-hex")).isFalse();
    }
}
