package com.devappmobile.flowfuel.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.devappmobile.flowfuel.user.UserRepository;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cobre o RateLimitFilter (FLOW-009 / M6). Religa o rate limiting (desligado no
 * application.properties de teste) so para esta classe. Usa Testcontainers para
 * prover um Redis real — os buckets sao armazenados no Redis, validando o
 * comportamento distribuido. Cada teste usa X-Forwarded-For distinto para isolar
 * o bucket por IP.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = "flowfuel.rate-limit.enabled=true")
class RateLimitFilterIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisUrl(DynamicPropertyRegistry registry) {
        registry.add("flowfuel.rate-limit.redis-url",
                () -> "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;

    @BeforeEach
    void limparBanco() {
        userRepository.deleteAll();
    }

    // -----------------------------------------------------------------------
    // Testes originais (regressao) — comportamento identico ao backend local
    // -----------------------------------------------------------------------

    @Test
    void login_apos5TentativasNoMesmoMinuto_retorna429ComRetryAfter() throws Exception {
        String ip = "10.0.0.1";

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                    .header("X-Forwarded-For", ip)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"email":"x@test.com","password":"errada"}
                            """))
                    .andExpect(status().isUnauthorized());
        }

        MvcResult blocked = mockMvc.perform(post("/api/v1/auth/login")
                .header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"x@test.com","password":"errada"}
                        """))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andReturn();

        assertThat(Long.parseLong(blocked.getResponse().getHeader("Retry-After"))).isPositive();

        JsonNode body = objectMapper.readTree(blocked.getResponse().getContentAsString());
        assertThat(body.get("properties").get("code").asText()).isEqualTo("RATE_LIMIT_EXCEEDED");
        assertThat(body.get("status").asInt()).isEqualTo(429);
    }

    @Test
    void forgotPassword_apos3Tentativas_retorna429() throws Exception {
        String ip = "10.0.0.2";

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/auth/forgot-password")
                    .header("X-Forwarded-For", ip)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"email":"naoexiste@test.com"}
                            """))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(post("/api/v1/auth/forgot-password")
                .header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"naoexiste@test.com"}
                        """))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void register_apos10Tentativas_retorna429() throws Exception {
        String ip = "10.0.0.3";

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/v1/auth/register")
                    .header("X-Forwarded-For", ip)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"email":"user%d@test.com","password":"senha123","name":"U"}
                            """.formatted(i)))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(post("/api/v1/auth/register")
                .header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"user11@test.com","password":"senha123","name":"U"}
                        """))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void login_ipsDiferentes_naoCompartilhamLimite() throws Exception {
        for (int i = 0; i < 6; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                    .header("X-Forwarded-For", "10.0.0.4")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"email":"x@test.com","password":"errada"}
                            """));
        }

        mockMvc.perform(post("/api/v1/auth/login")
                .header("X-Forwarded-For", "10.0.0.5")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"x@test.com","password":"errada"}
                        """))
                .andExpect(status().isUnauthorized());
    }

    // -----------------------------------------------------------------------
    // Novo teste M6: limite compartilhado entre duas "instancias" via Redis
    // -----------------------------------------------------------------------

    /**
     * Simula duas instancias da aplicacao compartilhando o mesmo Redis.
     * A "instancia 2" consome 4 tokens diretamente via ProxyManager; a 5a
     * requisicao via MockMvc (instancia 1) ainda e permitida; a 6a e bloqueada.
     * Sem Redis compartilhado, a 6a teria passado pois a instancia 1 so viu 1 req.
     */
    @Test
    void login_duasInstancias_compartilhamLimiteNoRedis() throws Exception {
        String ip = "10.1.0.1";
        String bucketKey = "rl:/api/v1/auth/login|" + ip;

        BucketConfiguration loginConfig = BucketConfiguration.builder()
                .addLimit(Bandwidth.builder().capacity(5)
                        .refillGreedy(5, Duration.ofMinutes(1)).build())
                .build();

        // Cria um segundo ProxyManager (simula instancia 2) apontando para o mesmo Redis
        String redisUrl = "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379);
        RedisClient client2 = RedisClient.create(RedisURI.create(redisUrl));
        try {
            var conn2 = client2.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
            LettuceBasedProxyManager<String> pm2 = LettuceBasedProxyManager.builderFor(conn2).build();
            BucketProxy bucket2 = pm2.builder().build(bucketKey, () -> loginConfig);

            // Instancia 2 consome 4 dos 5 tokens
            for (int i = 0; i < 4; i++) {
                assertThat(bucket2.tryConsume(1)).isTrue();
            }

            // 5a requisicao (instancia 1 via MockMvc): 1 token restante, deve passar
            mockMvc.perform(post("/api/v1/auth/login")
                    .header("X-Forwarded-For", ip)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"email":"x@test.com","password":"errada"}
                            """))
                    .andExpect(status().isUnauthorized()); // 401 = passou, nao bloqueado

            // 6a requisicao (instancia 1 via MockMvc): bucket esgotado no Redis
            mockMvc.perform(post("/api/v1/auth/login")
                    .header("X-Forwarded-For", ip)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"email":"x@test.com","password":"errada"}
                            """))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().exists("Retry-After"));

            conn2.close();
        } finally {
            client2.shutdown();
        }
    }
}
