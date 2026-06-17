package com.devappmobile.flowfuel.config;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;

@Component
public class JwtUtil {

    private final SecretKey secretKey;
    private final long accessTokenTtlMs;

    public JwtUtil(@Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-ttl-ms:900000}") long accessTokenTtlMs) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenTtlMs = accessTokenTtlMs;
    }

    public long getAccessTokenTtlMs() {
        return accessTokenTtlMs;
    }

    public String generateToken(String email, Long userId) {
        return Jwts.builder()
                .setSubject(email)
                .claim("userId", userId)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + accessTokenTtlMs))
                .signWith(secretKey)
                .compact();
    }

    public Optional<Claims> tryParse(String token) {
        try {
            return Optional.of(parseClaims(token));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public String extractEmail(String token) {
        return tryParse(token).map(Claims::getSubject).orElse(null);
    }

    public boolean validateToken(String token) {
        return tryParse(token).isPresent();
    }

    public Long extractUserId(String token) {
        return tryParse(token)
                .map(claims -> claims.get("userId"))
                .map(v -> Long.valueOf(v.toString()))
                .orElse(null);
    }

    private Claims parseClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(secretKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }
}
