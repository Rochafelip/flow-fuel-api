package com.devappmobile.flowfuel.config;

import io.github.bucket4j.Bandwidth;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.time.Duration;
import java.util.Map;

/**
 * Configuracao do rate limiting dos endpoints de autenticacao (FLOW-009).
 *
 * <p>Limites (token bucket, por IP):
 * <ul>
 *   <li>{@code POST /api/v1/auth/login} — 5 tentativas/minuto</li>
 *   <li>{@code POST /api/v1/auth/forgot-password} — 3 tentativas/hora</li>
 *   <li>{@code POST /api/v1/auth/register} — 10 tentativas/hora</li>
 *   <li>{@code POST /api/v1/auth/resend-activation} — 3 tentativas/hora</li>
 * </ul>
 *
 * <p>Habilitado por padrao. Desligue com {@code flowfuel.rate-limit.enabled=false}
 * (usado nos testes de integracao, que compartilham o IP 127.0.0.1 e excederiam
 * os limites). O filtro e exposto como bean {@link jakarta.servlet.Filter}, entao
 * o Spring Boot o registra automaticamente na cadeia de filtros do servlet.
 */
@Configuration
@ConditionalOnProperty(name = "flowfuel.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitingConfig {

    private static final String AUTH_BASE = "/api/v1/auth";

    // Apos o RequestIdFilter (HIGHEST_PRECEDENCE — requestId ja no MDC para o
    // ProblemDetail) e antes do JwtAuthenticationFilter / Spring Security.
    private static final int FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

    @Bean
    public RateLimitFilter rateLimitFilter() {
        Map<String, Bandwidth> limits = Map.of(
                AUTH_BASE + "/login",
                Bandwidth.builder().capacity(5).refillGreedy(5, Duration.ofMinutes(1)).build(),
                AUTH_BASE + "/forgot-password",
                Bandwidth.builder().capacity(3).refillGreedy(3, Duration.ofHours(1)).build(),
                AUTH_BASE + "/register",
                Bandwidth.builder().capacity(10).refillGreedy(10, Duration.ofHours(1)).build(),
                AUTH_BASE + "/resend-activation",
                Bandwidth.builder().capacity(3).refillGreedy(3, Duration.ofHours(1)).build());
        return new RateLimitFilter(limits, FILTER_ORDER);
    }
}
