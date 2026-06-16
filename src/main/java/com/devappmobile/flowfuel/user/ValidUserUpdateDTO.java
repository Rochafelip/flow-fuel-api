package com.devappmobile.flowfuel.user;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = UserUpdateDTOValidator.class)
public @interface ValidUserUpdateDTO {
    String message() default "Invalid user update";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}

class UserUpdateDTOValidator implements ConstraintValidator<ValidUserUpdateDTO, UserUpdateDTO> {
    @Override
    public boolean isValid(UserUpdateDTO dto, ConstraintValidatorContext context) {
        if (dto == null) {
            return true;
        }

        // If email is provided but empty or whitespace, it's invalid
        if (dto.email() != null && dto.email().trim().isEmpty()) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate("email must not be blank")
                    .addPropertyNode("email")
                    .addConstraintViolation();
            return false;
        }

        return true;
    }
}
