package com.devappmobile.flowfuel.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtUtilTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil("test-secret-key-for-unit-tests-only-32chars!!");
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
}
