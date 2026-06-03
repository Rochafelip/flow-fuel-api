package com.devappmobile.flowfuel.user;

import jakarta.validation.constraints.NotBlank;

/** Corpo de {@code POST /auth/activate}: token de ativacao (plaintext) recebido no link. */
public record ActivateAccountRequest(@NotBlank String token) {
}
