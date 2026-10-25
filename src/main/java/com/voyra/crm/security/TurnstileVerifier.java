package com.voyra.crm.security;

import com.voyra.crm.exception.CaptchaException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/**
 * Cloudflare Turnstile check for the public agency application create (spec 7.1). With no secret configured it lets
 * everything through (startup WARN, see TurnstileConfig); with one, it fails closed: a bad token is 400, and Cloudflare
 * being down or slow is 503 (Review Focus 5 - spam is the risk). The secret and the token are never logged.
 */
@Slf4j
public class TurnstileVerifier {

    public static final String VERIFY_URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";
    static final String FAILED = "Verification failed, please try again.";
    /** Cloudflare's documented maximum token length. */
    private static final int MAX_TOKEN = 2048;

    private final RestClient client;
    private final String secret;

    public TurnstileVerifier(RestClient.Builder builder, String secret) {
        this.client = builder.build();
        this.secret = secret;
    }

    /**
     * The visitor's address for Cloudflare's optional {@code remoteip}: the CF-Connecting-IP header Cloudflare sets, or null
     * (omitted) without it - the last X-Forwarded-For hop is our own proxy, and a wrong address only weakens the check.
     */
    public static String remoteIpOf(HttpServletRequest request) {
        String ip = request.getHeader("CF-Connecting-IP");
        return StringUtils.hasText(ip) ? ip.trim() : null;
    }

    public boolean enabled() {
        return StringUtils.hasText(secret);
    }

    public void verify(String token, String remoteIp) {
        if (!enabled()) {
            return;
        }
        if (!StringUtils.hasText(token) || token.length() > MAX_TOKEN) {
            throw new CaptchaException(HttpStatus.BAD_REQUEST, "CAPTCHA_FAILED", FAILED);
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("secret", secret);
        form.add("response", token);
        if (remoteIp != null) {
            form.add("remoteip", remoteIp);
        }
        Map<?, ?> body;
        try {
            body = client.post().uri(VERIFY_URL).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                    .retrieve().body(Map.class);
        } catch (RestClientException e) {
            log.warn("Turnstile verification unavailable: {}", e.getClass().getSimpleName());
            throw new CaptchaException(HttpStatus.SERVICE_UNAVAILABLE, "CAPTCHA_UNAVAILABLE", FAILED);
        }
        if (body == null) {
            log.warn("Turnstile verification returned no body");
            throw new CaptchaException(HttpStatus.SERVICE_UNAVAILABLE, "CAPTCHA_UNAVAILABLE", FAILED);
        }
        if (!Boolean.TRUE.equals(body.get("success"))) {
            log.info("Turnstile rejected an agency application: {}", body.get("error-codes"));
            throw new CaptchaException(HttpStatus.BAD_REQUEST, "CAPTCHA_FAILED", FAILED);
        }
    }
}
