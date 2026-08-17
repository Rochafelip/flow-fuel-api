package com.devappmobile.flowfuel.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Corpo de {@code POST /auth/activate}: email da conta e codigo de ativacao de 6 digitos recebido por email. */
public record ActivateAccountRequest(@NotBlank @Email String email, @NotBlank String token) {
}
