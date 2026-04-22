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
                .claim("role", role.name())
                .issuedAt(new Date(now))
                .expiration(new Date(now + accessExpiration))
                .signWith(secretKey)
                .compact();
    }

    public String generateRefreshToken(Long memberId) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(String.valueOf(memberId))
                .issuedAt(new Date(now))
                .expiration(new Date(now + refreshExpiration))
                .signWith(secretKey)
                .compact();
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

    public Role getRoleFromToken(String token) {
        String role = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .get("role", String.class);
        return Role.valueOf(role);
    }
}
