package com.devappmobile.flowfuel.config;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JwtUtilTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil("test-secret-key-for-unit-tests-only-32chars!!", 900_000L);
    }

    @Test
    void generateToken_deveRetornarTokenNaoNulo() {
        String token = jwtUtil.generateToken("user@test.com", 1L);
        assertThat(token).isNotNull().isNotBlank();
    }

    @Test
    void extractEmail_deveRetornarEmailCorreto() {
        String token = jwtUtil.generateToken("user@test.com", 1L);
        assertThat(jwtUtil.extractEmail(token)).isEqualTo("user@test.com");
    }

    @Test
    void extractUserId_deveRetornarIdCorreto() {
        String token = jwtUtil.generateToken("user@test.com", 42L);
        assertThat(jwtUtil.extractUserId(token)).isEqualTo(42L);
    }

    @Test
    void validateToken_comTokenValido_deveRetornarTrue() {
        String token = jwtUtil.generateToken("user@test.com", 1L);
        assertThat(jwtUtil.validateToken(token)).isTrue();
    }

    @Test
    void validateToken_comStringInvalida_deveRetornarFalse() {
        assertThat(jwtUtil.validateToken("token.invalido.qualquer")).isFalse();
    }

    @Test
    void validateToken_comTokenVazio_deveRetornarFalse() {
        assertThat(jwtUtil.validateToken("")).isFalse();
    }

    @Test
    void tryParse_comTokenValido_deveRetornarClaimsPresente() {
        String token = jwtUtil.generateToken("user@test.com", 42L);

        Optional<Claims> result = jwtUtil.tryParse(token);

        assertThat(result).isPresent();
        assertThat(result.get().getSubject()).isEqualTo("user@test.com");
        assertThat(result.get().get("userId", Integer.class)).isEqualTo(42);
    }

    @Test
    void tryParse_comTokenMalformado_deveRetornarVazio() {
        Optional<Claims> result = jwtUtil.tryParse("token.invalido.qualquer");

        assertThat(result).isEmpty();
    }

    @Test
    void tryParse_comStringVazia_deveRetornarVazio() {
        Optional<Claims> result = jwtUtil.tryParse("");

        assertThat(result).isEmpty();
    }

    @Test
    void tryParse_comTokenExpirado_deveRetornarVazio() {
        JwtUtil shortLived = new JwtUtil("test-secret-key-for-unit-tests-only-32chars!!", 0L);
        String expiredToken = shortLived.generateToken("user@test.com", 1L);

        Optional<Claims> result = jwtUtil.tryParse(expiredToken);

        assertThat(result).isEmpty();
    }
}
