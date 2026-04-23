package com.gongu.server.global.security.jwt;

import com.gongu.server.global.security.Role;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

@Component
@RequiredArgsConstructor
public class JwtProvider {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.access-expiration}")
    private long accessExpiration;

    @Value("${jwt.refresh-expiration}")
    private long refreshExpiration;

    private SecretKey secretKey;

    @PostConstruct
    public void init() {
        this.secretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
    }

    public String generateAccessToken(Long memberId, Role role) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(String.valueOf(memberId))
                .claim("type", "access")
                .claim("role", role.name())
                .issuedAt(new Date(now))
                .expiration(new Date(now + accessExpiration))
                .signWith(secretKey)
                .compact();
    }

    public String generateRefreshToken(Long memberId, Role role) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(String.valueOf(memberId))
                .claim("type", "refresh")
                .claim("role", role.name())
                .issuedAt(new Date(now))
                .expiration(new Date(now + refreshExpiration))
                .signWith(secretKey)
                .compact();
    }

    /**
     * Refresh Token 전용 검증. 서명·만료 확인 후 type=refresh 클레임을 추가로 검증한다.
     */
    public boolean validateRefreshToken(String token) {
        try {
            String type = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload()
                    .get("type", String.class);
            return "refresh".equals(type);
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Access Token 전용 검증. 서명·만료 확인 후 type=access 클레임을 추가로 검증한다.
     */
    public boolean validateAccessToken(String token) {
        try {
            String type = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload()
                    .get("type", String.class);
            return "access".equals(type);
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public Long getMemberIdFromToken(String token) {
        String subject = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
        try {
            return Long.parseLong(subject);
        } catch (NumberFormatException e) {
            throw new JwtException("Invalid member ID in token subject: " + subject, e);
        }
    }

    /**
     * 토큰에서 role 클레임을 추출한다.
     */
    public Role getRoleFromToken(String token) {
        String role = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .get("role", String.class);
        if (role == null) {
            throw new JwtException("role claim is missing");
        }
        try {
            return Role.valueOf(role);
        } catch (IllegalArgumentException e) {
            throw new JwtException("Unknown role claim: " + role, e);
        }
    }

    /**
     * Refresh Token을 파싱하여 memberId와 role을 한 번에 반환한다.
     * 내부적으로 서명·만료·type=refresh 검증을 수행하며, JWT를 한 번만 파싱한다.
     * 검증 실패 또는 클레임 추출 실패 시 JwtException을 던진다.
     */
    public RefreshTokenClaims parseRefreshToken(String token) {
        var claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        if (!"refresh".equals(claims.get("type", String.class))) {
            throw new JwtException("Not a refresh token");
        }

        Long memberId;
        try {
            memberId = Long.parseLong(claims.getSubject());
        } catch (NumberFormatException e) {
            throw new JwtException("Invalid member ID in token subject", e);
        }

        String roleName = claims.get("role", String.class);
        if (roleName == null) {
            throw new JwtException("role claim is missing");
        }
        Role role;
        try {
            role = Role.valueOf(roleName);
        } catch (IllegalArgumentException e) {
            throw new JwtException("Unknown role claim: " + roleName, e);
        }

        return new RefreshTokenClaims(memberId, role);
    }

    public record RefreshTokenClaims(Long memberId, Role role) {}
}
