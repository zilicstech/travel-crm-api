package com.voyra.crm.service.payments;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Exactly one PaymentGateway bean: stub unless app.payments.provider=razorpay. */
@Slf4j
@Configuration
public class PaymentGatewayConfig {

    @Bean
    @ConditionalOnProperty(name = "app.payments.provider", havingValue = "razorpay")
    public PaymentGateway razorpayPaymentGateway(@Value("${app.payments.razorpay.key-id}") String keyId,
                                                 @Value("${app.payments.razorpay.key-secret}") String keySecret,
                                                 @Value("${app.payments.razorpay.webhook-secret}") String webhookSecret,
                                                 @Value("${app.payments.razorpay.base-url}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(15_000);
        return new RazorpayPaymentGateway(RestClient.builder().requestFactory(factory), keyId, keySecret, webhookSecret, baseUrl);
    }

    @Bean
    @ConditionalOnProperty(name = "app.payments.provider", havingValue = "stub", matchIfMissing = true)
    public PaymentGateway stubPaymentGateway(@Value("${app.payments.simulate-enabled:true}") boolean simulateEnabled) {
        log.warn("Payment provider is STUB - no real payments; simulated payments {}", simulateEnabled
                ? "allowed on test wallets only" : "switched off (app.payments.simulate-enabled=false)");
        return new StubPaymentGateway(simulateEnabled);
    }
}
