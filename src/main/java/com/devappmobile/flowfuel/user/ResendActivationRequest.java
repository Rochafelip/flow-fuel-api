package com.devappmobile.flowfuel.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Corpo de {@code POST /auth/resend-activation}: reenvia o link de ativacao para um email. */
public record ResendActivationRequest(@NotBlank @Email String email) {
}
