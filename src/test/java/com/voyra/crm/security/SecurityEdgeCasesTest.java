package com.voyra.crm.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.cache.TenantCache;
import com.voyra.crm.context.TenantContext;
import com.voyra.crm.enums.UserType;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SecurityEdgeCasesTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @AfterEach
    void clear() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private static JwtService jwtService() {
        JwtService jwt = new JwtService();
        ReflectionTestUtils.setField(jwt, "jwtSecret", "test-only-jwt-secret-key-at-least-256-bits-long-for-hs256-signing");
        ReflectionTestUtils.setField(jwt, "jwtExpirationMs", 86_400_000L);
        return jwt;
    }

    @Test
    void aesPasswordsRoundTripAndLegacyBcryptHashesStillMatch() {
        AesPasswordEncoder encoder = new AesPasswordEncoder(KEY);
        String encoded = encoder.encode("Secret-1");

        assertThat(encoded).isNotEqualTo("Secret-1");
        assertThat(encoder.matches("Secret-1", encoded)).isTrue();
        assertThat(encoder.matches("Secret-2", encoded)).isFalse();
        assertThat(encoder.decode(encoded)).isEqualTo("Secret-1");

        String legacy = new BCryptPasswordEncoder(4).encode("Old-1");
        assertThat(encoder.matches("Old-1", legacy)).isTrue();
        assertThat(encoder.matches("Old-2", legacy)).isFalse();
    }

    @Test
    void onlyAgentsAndAccountantsAreStaffUsers() {
        assertThat(new CustomUserPrincipal("U", "u", UserType.AGENT, "T").isStaffUser()).isTrue();
        assertThat(new CustomUserPrincipal("U", "u", UserType.ACCOUNTANT, "T").isStaffUser()).isTrue();
        assertThat(new CustomUserPrincipal("U", "u", UserType.AGENCY_OWNER, "T").isStaffUser()).isFalse();
        assertThat(new CustomUserPrincipal("U", "u", UserType.SUPER_ADMIN, null).isStaffUser()).isFalse();
    }

    @Test
    void interceptorsAndTheFilterRefuseNullArguments() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        EntitlementInterceptor entitlements = new EntitlementInterceptor(new ObjectMapper(), null, null);
        TenantStatusInterceptor status = new TenantStatusInterceptor(new ObjectMapper());
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService());

        assertThatThrownBy(() -> entitlements.preHandle(null, res, "h")).isInstanceOf(NullPointerException.class).hasMessageContaining("request");
        assertThatThrownBy(() -> entitlements.preHandle(req, null, "h")).isInstanceOf(NullPointerException.class).hasMessageContaining("response");
        assertThatThrownBy(() -> entitlements.preHandle(req, res, null)).isInstanceOf(NullPointerException.class).hasMessageContaining("handler");
        assertThatThrownBy(() -> status.preHandle(null, res, "h")).isInstanceOf(NullPointerException.class).hasMessageContaining("request");
        assertThatThrownBy(() -> status.preHandle(req, null, "h")).isInstanceOf(NullPointerException.class).hasMessageContaining("response");
        assertThatThrownBy(() -> status.preHandle(req, res, null)).isInstanceOf(NullPointerException.class).hasMessageContaining("handler");
        assertThatThrownBy(() -> filter.doFilterInternal(null, res, mock(FilterChain.class))).isInstanceOf(NullPointerException.class).hasMessageContaining("request");
        assertThatThrownBy(() -> filter.doFilterInternal(req, null, mock(FilterChain.class))).isInstanceOf(NullPointerException.class).hasMessageContaining("response");
        assertThatThrownBy(() -> filter.doFilterInternal(req, res, null)).isInstanceOf(NullPointerException.class).hasMessageContaining("filterChain");
    }

    @Test
    void aNonBearerOrInvalidTokenPassesThroughUnauthenticated() throws Exception {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService());
        for (String header : new String[]{"Basic dXNlcjpwYXNz", "Bearer not-a-jwt"}) {
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.addHeader("Authorization", header);
            MockHttpServletResponse res = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilterInternal(req, res, chain);

            verify(chain).doFilter(req, res);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }
    }

    @Test
    void aTokenForAnUnknownTenantIs401() throws Exception {
        JwtService jwt = jwtService();
        String token = jwt.generateToken("owner@example.test", UserType.AGENCY_OWNER, "U1", "SEC-GONE");
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        new JwtAuthenticationFilter(jwt).doFilterInternal(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("Invalid or inactive tenant");
        verifyNoInteractions(chain);
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void aTokenWithoutARoleIsAnAgentAndABlankTenantIsLeftOut() {
        JwtService jwt = jwtService();
        Claims claims = jwt.validateAndGetClaims(jwt.generateToken("x@example.test", null, "U1", " "));
        assertThat(claims.get("role")).isEqualTo("AGENT");
        assertThat(claims.get("tenantId")).isNull();
        assertThat(jwt.validateAndGetClaims("garbage")).isNull();
    }

    @Test
    void withNobodySignedInTheCurrentUserIsRequiredButAbsent() {
        assertThat(SecurityContextUtil.getCurrentUser()).isEmpty();
        assertThatThrownBy(SecurityContextUtil::getCurrentUserOrThrow)
                .isInstanceOf(IllegalStateException.class).hasMessage("No authenticated user in security context");
    }

    @Test
    void theSystemActorNeedsATenant() {
        assertThatThrownBy(() -> SystemActor.call(null, () -> 1)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The system actor needs a tenant");
        assertThatThrownBy(() -> SystemActor.call(" ", () -> 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNestedSystemCallRestoresTheOuterActorAndTenant() {
        TenantContext.setTenantId("OUTER-REQ");
        String inner = SystemActor.call("T1", () -> SystemActor.call("T2", () -> {
            assertThat(SystemActor.principal()).get().extracting(CustomUserPrincipal::tenantId).isEqualTo("T2");
            return TenantContext.getTenantId();
        }) + "|" + TenantContext.getTenantId() + "|" + SystemActor.principal().get().tenantId());

        assertThat(inner).isEqualTo("T2|T1|T1");
        assertThat(TenantContext.getTenantId()).isEqualTo("OUTER-REQ");
        assertThat(SystemActor.isActive()).isFalse();
    }

    @Test
    void aBlankOuterTenantIsClearedAfterASystemCall() {
        TenantContext.setTenantId(" ");
        SystemActor.call("T1", () -> null);
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void aNullCallerSharesOneWindowAndOldWindowsAreSweptWhenTheMapGrows() {
        AtomicLong now = new AtomicLong(0);
        InquiryRateLimiter limiter = new InquiryRateLimiter(now::get);
        for (int i = 0; i < InquiryRateLimiter.LIMIT; i++) limiter.check(null);
        assertThatThrownBy(() -> limiter.check(null)).isInstanceOf(InquiryRateLimiter.TooManyInquiriesException.class);

        for (int i = 0; i <= 10_000; i++) limiter.check("caller-" + i);
        now.set(InquiryRateLimiter.WINDOW_MS);
        // the sweep drops every expired window, so the null caller starts over
        assertThatCode(() -> limiter.check(null)).doesNotThrowAnyException();
        assertThat(ReflectionTestUtils.getField(limiter, FixedWindowRateLimiter.class, "windows")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.MAP).hasSize(1);
    }

    @Test
    void theProductionLimitersUseTheSystemClock() {
        assertThatCode(() -> new KycUploadRateLimiter().check("1.2.3.4")).doesNotThrowAnyException();
        InquiryRateLimiter inquiry = new InquiryRateLimiter();
        assertThatCode(() -> inquiry.check("1.2.3.4")).doesNotThrowAnyException();

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("CF-Connecting-IP", "9.9.9.9");
        req.setRemoteAddr("5.5.5.5");
        assertThat(inquiry.callerOf(req)).as("CF header untrusted by default").isEqualTo("5.5.5.5");
    }

    @Test
    void aTrustedButBlankCfHeaderOrAnEmptyLastHopFallsBack() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("CF-Connecting-IP", " ");
        req.addHeader("X-Forwarded-For", " ");
        req.setRemoteAddr("5.5.5.5");
        assertThat(InquiryRateLimiter.callerOf(req, true)).isEqualTo("5.5.5.5");

        MockHttpServletRequest noCf = new MockHttpServletRequest();
        noCf.setRemoteAddr("7.7.7.7");
        assertThat(InquiryRateLimiter.callerOf(noCf, true)).isEqualTo("7.7.7.7");

        MockHttpServletRequest trailing = new MockHttpServletRequest();
        trailing.addHeader("X-Forwarded-For", "1.1.1.1, ");
        trailing.setRemoteAddr("6.6.6.6");
        assertThat(InquiryRateLimiter.callerOf(trailing, false)).isEqualTo("6.6.6.6");
    }

    @Test
    void turnstileWithoutAnAddressSendsNoRemoteIpAndAnEmptyAnswerIs503() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(TurnstileVerifier.VERIFY_URL)).andExpect(method(HttpMethod.POST))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("remoteip"))))
                .andRespond(withSuccess());

        assertThatThrownBy(() -> new TurnstileVerifier(builder, "s3cret").verify("tok", null))
                .isInstanceOfSatisfying(com.voyra.crm.exception.CaptchaException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getCode()).isEqualTo("CAPTCHA_UNAVAILABLE");
                });
        server.verify();
    }
}
