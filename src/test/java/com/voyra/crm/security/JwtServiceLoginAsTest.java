package com.voyra.crm.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceLoginAsTest {

    @Test
    void aLoginAsTokenIsTheOwnersForAnHourAndNamesTheAdmin() {
        JwtService jwt = new JwtService();
        ReflectionTestUtils.setField(jwt, "jwtSecret", "test-only-jwt-secret-key-at-least-256-bits-long-for-hs256-signing");
        ReflectionTestUtils.setField(jwt, "jwtExpirationMs", 86_400_000L);
        ReflectionTestUtils.setField(jwt, "loginAsExpirationMs", 3_600_000L);

        Claims c = jwt.validateAndGetClaims(jwt.generateLoginAsToken("asha@sky.test", "T1", "ADM1"));

        assertThat(c.getSubject()).isEqualTo("asha@sky.test");
        assertThat(c.get("role")).isEqualTo("AGENCY_OWNER");
        assertThat(c.get("userId")).isEqualTo("T1");
        assertThat(c.get("tenantId")).isEqualTo("T1");
        assertThat(c.get("actBy")).isEqualTo("ADM1");
        assertThat(c.getExpiration().getTime() - c.getIssuedAt().getTime()).isEqualTo(3_600_000L);
        assertThat(jwt.validateAndGetClaims(jwt.generateToken("a@b.test", null, "U1", null)).get("actBy")).isNull();
    }
}
