package com.devappmobile.flowfuel.user;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Resposta de {@code POST /auth/resend-activation}.
 *
 * <p>Por seguranca (anti-enumeracao de emails) a {@code message} e sempre a
 * mesma, exista ou nao o email e esteja ou nao a conta pendente. O campo
 * {@code activationToken} so e preenchido quando
 * {@code flowfuel.account-activation.expose-token=true} (dev/testes) — em
 * producao ele e omitido e o token chega apenas pelo canal de entrega (email).
 * Veja {@link AccountActivationNotifier}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AccountActivationResponse(String message, String activationToken) {

    public static AccountActivationResponse standard() {
        return new AccountActivationResponse(
                "Se houver uma conta pendente associada a este email, enviaremos um novo link de ativação.",
                null);
    }

    public AccountActivationResponse withToken(String token) {
        return new AccountActivationResponse(message, token);
    }
}
