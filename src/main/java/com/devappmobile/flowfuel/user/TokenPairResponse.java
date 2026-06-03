package com.devappmobile.flowfuel.user;

/**
 * Resposta de autenticacao (ADR-003): par access + refresh token.
 * {@code expiresIn} e o TTL do access em segundos.
 */
public record TokenPairResponse(String accessToken, String refreshToken, long expiresIn) {
}
