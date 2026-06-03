package com.devappmobile.flowfuel.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.devappmobile.flowfuel.user.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cobre o RateLimitFilter (FLOW-009). Religa o rate limiting (desligado no
 * application.properties de teste) so para esta classe. Cada teste usa um
 * X-Forwarded-For distinto para isolar o bucket por IP.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "flowfuel.rate-limit.enabled=true")
class RateLimitFilterIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;

    @BeforeEach
    void limparBanco() {
        userRepository.deleteAll();
    }

    @Test
    void login_apos5TentativasNoMesmoMinuto_retorna429ComRetryAfter() throws Exception {
        String ip = "10.0.0.1";

        // 5 tentativas permitidas (credenciais invalidas -> 401, mas consomem token).
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                    .header("X-Forwarded-For", ip)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"email":"x@test.com","password":"errada"}
                            """))
                    .andExpect(status().isUnauthorized());
        }

        // 6a tentativa: bloqueada.
        MvcResult blocked = mockMvc.perform(post("/api/v1/auth/login")
                .header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"x@test.com","password":"errada"}
                        """))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andReturn();

        // Retry-After e um numero de segundos positivo.
        assertThat(Long.parseLong(blocked.getResponse().getHeader("Retry-After"))).isPositive();

        // Corpo ProblemDetail com o code do catalogo de erros (em properties.code).
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
        // Esgota o limite de um IP...
        for (int i = 0; i < 6; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                    .header("X-Forwarded-For", "10.0.0.4")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"email":"x@test.com","password":"errada"}
                            """));
        }

        // ...outro IP continua liberado (401 por credenciais, nao 429).
        mockMvc.perform(post("/api/v1/auth/login")
                .header("X-Forwarded-For", "10.0.0.5")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"x@test.com","password":"errada"}
                        """))
                .andExpect(status().isUnauthorized());
    }
}
