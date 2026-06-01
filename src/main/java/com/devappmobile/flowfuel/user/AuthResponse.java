package com.devappmobile.flowfuel.user;

/**
 * Resposta de autenticacao que acompanha os dados do usuario.
 *
 * <p>Usada no cadastro ({@code POST /auth/register}): alem de criar a conta,
 * ja emite o par de tokens (ADR-003), permitindo que o cliente entre logado
 * sem uma segunda chamada a {@code /auth/login}.
 */
public record AuthResponse(
        UserResponseDTO user,
        String accessToken,
        String refreshToken,
        long expiresIn) {

    public static AuthResponse of(UserResponseDTO user, TokenPairResponse tokens) {
        return new AuthResponse(user, tokens.accessToken(), tokens.refreshToken(), tokens.expiresIn());
    }
}
