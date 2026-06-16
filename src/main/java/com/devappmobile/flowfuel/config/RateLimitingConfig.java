package com.devappmobile.flowfuel.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.time.Duration;
import java.util.Map;

/**
 * Configuracao do rate limiting dos endpoints de autenticacao (FLOW-009 / M6).
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
 * (usado nos testes de integracao, que compartilham 127.0.0.1 e excederiam os limites).
 *
 * <p>Backend: Redis via bucket4j-redis + Lettuce. URL configurada por
 * {@code flowfuel.rate-limit.redis-url} (env var {@code REDIS_URL}).
 * Se o Redis ficar indisponivel em runtime, o filtro aplica fail-open.
 */
@Configuration
@ConditionalOnProperty(name = "flowfuel.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitingConfig {

    private static final String AUTH_BASE = "/api/v1/auth";

    // Apos o RequestIdFilter (HIGHEST_PRECEDENCE — requestId ja no MDC para o
    // ProblemDetail) e antes do JwtAuthenticationFilter / Spring Security.
    private static final int FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

    @Value("${flowfuel.rate-limit.redis-url:redis://localhost:6379}")
    private String redisUrl;

    @Bean(destroyMethod = "shutdown")
    RedisClient rateLimitRedisClient() {
        return RedisClient.create(RedisURI.create(redisUrl));
    }

    @Bean
    LettuceBasedProxyManager<String> rateLimitProxyManager(RedisClient rateLimitRedisClient) {
        var connection = rateLimitRedisClient.connect(
                RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
        return LettuceBasedProxyManager.builderFor(connection).build();
    }

    @Bean
    RateLimitFilter rateLimitFilter(LettuceBasedProxyManager<String> rateLimitProxyManager) {
        Map<String, BucketConfiguration> limits = Map.of(
                AUTH_BASE + "/login",
                BucketConfiguration.builder()
                        .addLimit(Bandwidth.builder().capacity(5)
                                .refillGreedy(5, Duration.ofMinutes(1)).build())
                        .build(),
                AUTH_BASE + "/forgot-password",
                BucketConfiguration.builder()
                        .addLimit(Bandwidth.builder().capacity(3)
                                .refillGreedy(3, Duration.ofHours(1)).build())
                        .build(),
                AUTH_BASE + "/register",
                BucketConfiguration.builder()
                        .addLimit(Bandwidth.builder().capacity(10)
                                .refillGreedy(10, Duration.ofHours(1)).build())
                        .build(),
                AUTH_BASE + "/resend-activation",
                BucketConfiguration.builder()
                        .addLimit(Bandwidth.builder().capacity(3)
                                .refillGreedy(3, Duration.ofHours(1)).build())
                        .build());
        return new RateLimitFilter(limits, rateLimitProxyManager, FILTER_ORDER);
    }
}
