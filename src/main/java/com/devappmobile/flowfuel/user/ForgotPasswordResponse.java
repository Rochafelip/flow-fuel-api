package com.devappmobile.flowfuel.user;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Resposta de {@code POST /auth/forgot-password}.
 *
 * <p>Por seguranca (anti-enumeracao de emails) a {@code message} e sempre a
 * mesma, exista ou nao o email. O campo {@code resetToken} so e preenchido
 * quando {@code flowfuel.password-reset.expose-token=true} (perfil dev) —
 * em producao ele e omitido e o token chega apenas pelo canal de entrega
 * (email). Veja {@link PasswordResetNotifier}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ForgotPasswordResponse(String message, String resetToken) {

    public static ForgotPasswordResponse standard() {
        return new ForgotPasswordResponse(
                "Se houver uma conta associada a este email, enviaremos instruções de redefinição de senha.",
                null);
    }

    public ForgotPasswordResponse withToken(String token) {
        return new ForgotPasswordResponse(message, token);
    }
}
