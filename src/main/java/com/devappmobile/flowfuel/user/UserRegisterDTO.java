package com.devappmobile.flowfuel.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserRegisterDTO {

    @NotBlank
    @Email
    private String email;

    @NotBlank
    @Size(min = 6, message = "Senha deve ter pelo menos 6 caracteres")
    private String password;

    private String name;
    private String phone;
}
