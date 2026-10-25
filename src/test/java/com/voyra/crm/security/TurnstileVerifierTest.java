package com.voyra.crm.security;

import com.voyra.crm.exception.CaptchaException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Review Focus 5: with a secret set, /apply create fails closed when the token is bad or Cloudflare cannot answer. */
class TurnstileVerifierTest {

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    private TurnstileVerifier verifier(String secret) {
        return new TurnstileVerifier(builder, secret);
    }

    @Test
    void validTokenPasses() {
        server.expect(requestTo(TurnstileVerifier.VERIFY_URL)).andExpect(method(HttpMethod.POST))
                .andExpect(content().formDataContains(java.util.Map.of("secret", "s3cret", "response", "tok", "remoteip", "1.2.3.4")))
                .andRespond(withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON));

        assertThatCode(() -> verifier("s3cret").verify("tok", "1.2.3.4")).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void rejectedTokenIs400() {
        server.expect(requestTo(TurnstileVerifier.VERIFY_URL))
                .andRespond(withSuccess("{\"success\":false,\"error-codes\":[\"invalid-input-response\"]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> verifier("s3cret").verify("tok", "1.2.3.4"))
                .isInstanceOfSatisfying(CaptchaException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getCode()).isEqualTo("CAPTCHA_FAILED");
                    assertThat(e.getMessage()).isEqualTo("Verification failed, please try again.");
                });
    }

    @Test
    void missingTokenIs400WithoutCallingCloudflare() {
        server.expect(never(), requestTo(TurnstileVerifier.VERIFY_URL));

        assertThatThrownBy(() -> verifier("s3cret").verify(" ", "1.2.3.4"))
                .isInstanceOfSatisfying(CaptchaException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> verifier("s3cret").verify("x".repeat(2049), "1.2.3.4"))
                .isInstanceOfSatisfying(CaptchaException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        server.verify();
    }

    @Test
    void cloudflareDownFailsClosedWith503() {
        server.expect(requestTo(TurnstileVerifier.VERIFY_URL)).andRespond(withServerError());

        assertThatThrownBy(() -> verifier("s3cret").verify("tok", "1.2.3.4"))
                .isInstanceOfSatisfying(CaptchaException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getCode()).isEqualTo("CAPTCHA_UNAVAILABLE");
                    assertThat(e.getMessage()).isEqualTo("Verification failed, please try again.");
                });
    }

    @Test
    void timeoutFailsClosedWith503() {
        server.expect(requestTo(TurnstileVerifier.VERIFY_URL)).andRespond(request -> {
            throw new SocketTimeoutException("Read timed out");
        });

        assertThatThrownBy(() -> verifier("s3cret").verify("tok", "1.2.3.4"))
                .isInstanceOfSatisfying(CaptchaException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void unsetSecretSkipsVerification() {
        server.expect(never(), requestTo(TurnstileVerifier.VERIFY_URL));

        assertThatCode(() -> verifier("").verify(null, "1.2.3.4")).doesNotThrowAnyException();
        assertThatCode(() -> verifier(null).verify("tok", "1.2.3.4")).doesNotThrowAnyException();
        assertThat(verifier("").enabled()).isFalse();
        server.verify();
    }
}
