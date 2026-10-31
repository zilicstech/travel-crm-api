package com.voyra.crm.service.payments;

import com.voyra.crm.exception.SupplierException;
import com.voyra.crm.models.GatewayOrderCreated;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Gateway selection, the stub's fixed answers, and Razorpay's missing keys and failed calls. */
class PaymentGatewayCoverageTest {

    private static RazorpayPaymentGateway razorpay(RestClient.Builder builder) {
        return new RazorpayPaymentGateway(builder, "rzp_test_key", "key_secret", "whsec_test", "https://api.razorpay.com");
    }

    @Test
    void theConfigBuildsARazorpayGatewayWithItsKeysOrTheStub() {
        PaymentGatewayConfig config = new PaymentGatewayConfig();

        PaymentGateway rp = config.razorpayPaymentGateway("rzp_test_key", "key_secret", "whsec_test", "https://api.example.test");
        PaymentGateway stubOn = config.stubPaymentGateway(true);
        PaymentGateway stubOff = config.stubPaymentGateway(false);

        assertThat(rp.name()).isEqualTo("razorpay");
        assertThat(rp.publicKeyId()).isEqualTo("rzp_test_key");
        assertThat(rp.simulateEnabled()).isFalse();
        assertThat(stubOn.name()).isEqualTo("stub");
        assertThat(stubOn.simulateEnabled()).isTrue();
        assertThat(stubOff.simulateEnabled()).isFalse();
    }

    @Test
    void razorpayNeedsAllThreeSecrets() {
        String message = "app.payments.provider=razorpay needs RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET and RAZORPAY_WEBHOOK_SECRET";
        assertThatThrownBy(() -> new RazorpayPaymentGateway(RestClient.builder(), " ", "s", "w", "https://x.test"))
                .isInstanceOf(IllegalStateException.class).hasMessage(message);
        assertThatThrownBy(() -> new RazorpayPaymentGateway(RestClient.builder(), "k", null, "w", "https://x.test"))
                .hasMessage(message);
        assertThatThrownBy(() -> new RazorpayPaymentGateway(RestClient.builder(), "k", "s", "", "https://x.test"))
                .hasMessage(message);
    }

    @Test
    void aFailedRazorpayCallNamesOnlyTheErrorType() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.razorpay.com/v1/orders/order_X/payments"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"error\":\"secret detail\"}"));

        assertThatThrownBy(() -> razorpay(builder).fetchOrderPayments("order_X"))
                .isInstanceOf(SupplierException.class)
                .hasMessage("Razorpay call failed: BadRequest")
                .hasMessageNotContaining("secret detail");
        server.verify();
    }

    @Test
    void anEmptyRazorpayResponseIsAFailure() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.razorpay.com/v1/orders")).andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> razorpay(builder).createOrder(BigDecimal.ONE, "R1", Map.of()))
                .isInstanceOf(SupplierException.class).hasMessage("Razorpay returned an empty response");
    }

    @Test
    void aWebhookWithoutABodyIsRejected() {
        assertThat(razorpay(RestClient.builder()).verifyWebhook(null, "abc")).isFalse();
    }

    @Test
    void theStubMakesAnUnpaidOrderInPaiseAndAcceptsAnyWebhook() {
        StubPaymentGateway stub = new StubPaymentGateway(true);

        GatewayOrderCreated order = stub.createOrder(new BigDecimal("12.34"), "R1", Map.of());

        assertThat(order.orderId()).startsWith("order_stub_").doesNotContain("-");
        assertThat(order.amountPaise()).isEqualTo(1234L);
        assertThat(order.currency()).isEqualTo("INR");
        assertThat(stub.fetchOrderPayments(order.orderId())).isEmpty();
        assertThat(stub.verifyWebhook("{}", null)).isTrue();
        assertThat(stub.publicKeyId()).isEqualTo("stub");
    }
}
