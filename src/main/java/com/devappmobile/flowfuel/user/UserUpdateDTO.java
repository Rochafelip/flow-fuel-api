package com.devappmobile.flowfuel.user;

import jakarta.validation.constraints.Email;

public record UserUpdateDTO(
        @Email String email,
        String name,
        String phone) {}
