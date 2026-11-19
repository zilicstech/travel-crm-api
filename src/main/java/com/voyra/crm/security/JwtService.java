package com.voyra.crm.security;

import com.voyra.crm.enums.UserType;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

@Service
public class JwtService {

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Value("${app.jwt.expiration-ms}")
    private long jwtExpirationMs;

    @Value("${app.jwt.login-as-expiration-ms:3600000}")
    private long loginAsExpirationMs;

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(String username, UserType role, String userId, String tenantId) {
        return build(username, role, userId, tenantId, jwtExpirationMs, null);
    }

    /**
     * An agency owner's token minted for a platform admin who logs in to the agency: short-lived, and {@code actBy}
     * names the admin so a token can be traced back. The filter treats it as the owner's.
     */
    public String generateLoginAsToken(String ownerEmail, String tenantId, String adminId) {
        return build(ownerEmail, UserType.AGENCY_OWNER, tenantId, tenantId, loginAsExpirationMs, adminId);
    }

    private String build(String username, UserType role, String userId, String tenantId, long lifetimeMs, String actBy) {
        Date now = new Date();
        var builder = Jwts.builder()
                .subject(username)
                .claim("role", role != null ? role.name() : UserType.AGENT.name())
                .claim("userId", userId)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + lifetimeMs));

        if (tenantId != null && !tenantId.isBlank()) {
            builder.claim("tenantId", tenantId);
        }
        if (actBy != null) {
            builder.claim("actBy", actBy);
        }
        return builder.signWith(getSigningKey()).compact();
    }

    /** Returns null for any invalid token - callers treat null as "unauthenticated". */
    public Claims validateAndGetClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException | MalformedJwtException | SignatureException | IllegalArgumentException e) {
            return null;
        }
    }

    public List<GrantedAuthority> getAuthoritiesFromClaims(Claims claims) {
        return List.of(UserType.fromString(claims.get("role", String.class)).toGrantedAuthority());
    }

    public CustomUserPrincipal getPrincipalFromClaims(Claims claims) {
        return new CustomUserPrincipal(
                claims.get("userId", String.class),
                claims.getSubject(),
                UserType.fromString(claims.get("role", String.class)),
                claims.get("tenantId", String.class));
    }
}
