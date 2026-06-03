package com.devappmobile.flowfuel.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Fail-fast do segredo JWT em producao (FLOW-003).
 *
 * <p>No perfil {@code prod}, {@code jwt.secret} vem de {@code ${JWT_SECRET}} sem
 * fallback. Se a variavel de ambiente estiver ausente ou for curta demais para
 * HS256, a aplicacao NAO sobe — evitando que entre no ar com um segredo
 * publico/conhecido, o que permitiria a forja de tokens.
 */
@Configuration
@Profile("prod")
public class JwtProdValidator {

    /** Tamanho minimo em bytes exigido pelo HS256 (256 bits). */
    private static final int MIN_SECRET_LENGTH = 32;

    @Value("${jwt.secret:}")
    private String jwtSecret;

    @PostConstruct
    void validate() {
        if (jwtSecret == null || jwtSecret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "JWT_SECRET environment variable must be set and at least "
                            + MIN_SECRET_LENGTH + " characters long in production.");
        }
    }
}
