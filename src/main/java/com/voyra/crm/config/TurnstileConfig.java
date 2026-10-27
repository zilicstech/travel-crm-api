package com.voyra.crm.config;

import com.voyra.crm.security.TurnstileVerifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/** The Turnstile verifier with short timeouts; an unset TURNSTILE_SECRET is announced at startup (louder on Cloud Run). */
@Slf4j
@Configuration
public class TurnstileConfig {

    @Bean
    public TurnstileVerifier turnstileVerifier(@Value("${app.turnstile.secret:}") String secret, Environment environment) {
        if (!StringUtils.hasText(secret)) {
            if (environment.matchesProfiles("cloudrun")) {
                log.error("TURNSTILE_SECRET is not set - public agency applications are NOT CAPTCHA-protected on this deployment");
            } else {
                log.warn("TURNSTILE_SECRET is not set - CAPTCHA on public agency applications is skipped");
            }
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(5_000);
        return new TurnstileVerifier(RestClient.builder().requestFactory(factory), secret);
    }
}
