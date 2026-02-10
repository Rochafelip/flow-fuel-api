package com.devappmobile.flowfuel.config;

import io.jsonwebtoken.*;
import org.springframework.stereotype.Component;
import java.util.Base64;
import java.security.SecureRandom;
import java.util.Date;

@Component
public class JwtUtil {
    private final String SECRET_KEY;
    private final long EXPIRATION_TIME = 86400000; // 24 horas
    
    public JwtUtil() {
        // Gera chave aleatória de 64 bytes
        byte[] key = new byte[64];
        new SecureRandom().nextBytes(key);
        this.SECRET_KEY = Base64.getEncoder().encodeToString(key);
    }

    public String generateToken(String email) {
        return Jwts.builder()
                .setSubject(email)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + EXPIRATION_TIME))
                .signWith(SignatureAlgorithm.HS512, SECRET_KEY) 
                .compact();
    }

    public String extractEmail(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(SECRET_KEY)
                .build()
                .parseClaimsJws(token)
                .getBody()
                .getSubject();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parserBuilder()
                .setSigningKey(SECRET_KEY)
                .build()
                .parseClaimsJws(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}