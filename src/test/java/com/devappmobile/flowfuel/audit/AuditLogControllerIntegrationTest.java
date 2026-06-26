package com.devappmobile.flowfuel.audit;

import com.devappmobile.flowfuel.user.UserRepository;
import com.devappmobile.flowfuel.user.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AuditLogControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void limparBanco() {
        auditLogRepository.deleteAll();
        userRepository.deleteAll();
    }

    private String obterToken(String email, boolean admin) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"senha123","name":"User"}
                        """.formatted(email)));
        userRepository.findByEmail(email).ifPresent(u -> {
            u.setStatus(UserStatus.ACTIVE);
            u.setAdmin(admin);
            userRepository.save(u);
        });
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"senha123"}
                        """.formatted(email)))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    @Test
    void getAuditLogs_comoAdmin_retornaPaginaIncluindoOLoginQueAcabouDeAcontecer() throws Exception {
        String token = obterToken("admin@test.com", true);

        mockMvc.perform(get("/api/v1/audit-logs")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].action").value("LOGIN"));
    }

    @Test
    void getAuditLogs_comoUsuarioComum_retorna403() throws Exception {
        String token = obterToken("comum@test.com", false);

        mockMvc.perform(get("/api/v1/audit-logs")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    /** Registra um usuário comum, já ativo, sem efetuar login. */
    private void registrarEAtivar(String email, String password) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s","name":"User"}
                        """.formatted(email, password)));
        userRepository.findByEmail(email).ifPresent(u -> {
            u.setStatus(UserStatus.ACTIVE);
            userRepository.save(u);
        });
    }

    @Test
    void getAuditLogs_comoAdmin_contemRegistrosDasCincoAcoesAuditadas() throws Exception {
        // --- PASSWORD_RESET: forgot-password + reset-password ---
        registrarEAtivar("reset@test.com", "senha123");
        MvcResult forgotResult = mockMvc.perform(post("/api/v1/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"reset@test.com"}
                        """))
                .andExpect(status().isOk())
                .andReturn();
        String resetToken = objectMapper.readTree(forgotResult.getResponse().getContentAsString())
                .get("resetToken").asText();
        assertThat(resetToken).isNotBlank();

        mockMvc.perform(post("/api/v1/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token":"%s","newPassword":"novaSenha456"}
                        """.formatted(resetToken)))
                .andExpect(status().isNoContent());

        // --- PASSWORD_CHANGE: login (com a senha já redefinida) + change-password ---
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"reset@test.com","password":"novaSenha456"}
                        """))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode loginBody = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String access = loginBody.get("accessToken").asText();
        long userId = extractUserId(access);

        mockMvc.perform(put("/api/v1/auth/" + userId + "/password")
                .header("Authorization", "Bearer " + access)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"currentPassword":"novaSenha456","newPassword":"outraSenha789"}
                        """))
                .andExpect(status().isNoContent());

        // --- ACCOUNT_ACTIVATION: register (sem ativar) + resend-activation + activate ---
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"ativar@test.com","password":"senha123","name":"Ativar"}
                        """));
        MvcResult resendResult = mockMvc.perform(post("/api/v1/auth/resend-activation")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"ativar@test.com"}
                        """))
                .andExpect(status().isOk())
                .andReturn();
        String activationToken = objectMapper.readTree(resendResult.getResponse().getContentAsString())
                .get("activationToken").asText();
        assertThat(activationToken).isNotBlank();

        mockMvc.perform(post("/api/v1/auth/activate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token":"%s"}
                        """.formatted(activationToken)))
                .andExpect(status().isOk());

        // --- ACCOUNT_DELETION: delete-user do próprio usuário "reset@test.com" ---
        mockMvc.perform(delete("/api/v1/auth/" + userId)
                .header("Authorization", "Bearer " + access))
                .andExpect(status().isOk());

        // --- Verificação via admin: as 5 ações devem estar visíveis na auditoria ---
        String adminToken = obterToken("admin@test.com", true);

        MvcResult auditResult = mockMvc.perform(get("/api/v1/audit-logs")
                .param("size", "50")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode auditBody = objectMapper.readTree(auditResult.getResponse().getContentAsString());
        Set<String> actions = new HashSet<>();
        StreamSupport.stream(auditBody.get("content").spliterator(), false)
                .forEach(node -> actions.add(node.get("action").asText()));

        assertThat(actions).contains(
                "PASSWORD_RESET",
                "PASSWORD_CHANGE",
                "ACCOUNT_ACTIVATION",
                "ACCOUNT_DELETION");
    }

    private long extractUserId(String accessToken) {
        String payload = new String(java.util.Base64.getUrlDecoder()
                .decode(accessToken.split("\\.")[1]));
        try {
            return objectMapper.readTree(payload).get("userId").asLong();
        } catch (Exception e) {
            throw new IllegalStateException("token inesperado", e);
        }
    }
}
