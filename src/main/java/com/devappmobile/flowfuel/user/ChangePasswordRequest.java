package com.devappmobile.flowfuel.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        @NotBlank String currentPassword,
        @NotBlank @Size(min = 6, message = "Senha deve ter pelo menos 6 caracteres") String newPassword) {
}
