package com.devappmobile.flowfuel.user;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class UserControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void limparBanco() {
        userRepository.deleteAll();
    }

    private MvcResult registrar(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s","name":"Teste"}
                        """.formatted(email, password)))
                .andReturn();
    }

    private String obterToken(String email, String password) throws Exception {
        registrar(email, password);
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s"}
                        """.formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void register_comDadosValidos_retorna200ECorpoSemSenha() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"novo@test.com","password":"senha123","name":"Novo"}
                        """))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("id").asLong()).isPositive();
        assertThat(body.get("email").asText()).isEqualTo("novo@test.com");
        assertThat(body.has("password")).isFalse();
    }

    @Test
    void register_comEmailDuplicado_retorna400() throws Exception {
        registrar("dup@test.com", "senha123");

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"dup@test.com","password":"outrasenha"}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_comSenhaCurta_retorna400() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"curta@test.com","password":"abc"}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_comCredenciaisValidas_retornaTokenNoCorpo() throws Exception {
        registrar("login@test.com", "senha123");

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"login@test.com","password":"senha123"}
                        """))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("token").asText()).isNotBlank();
    }

    @Test
    void login_comSenhaErrada_retorna401() throws Exception {
        registrar("errada@test.com", "senha123");

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"errada@test.com","password":"senhaerrada"}
                        """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getProfile_autenticado_retornaPerfilSemSenha() throws Exception {
        MvcResult registerResult = registrar("perfil@test.com", "senha123");
        long userId = objectMapper.readTree(registerResult.getResponse().getContentAsString()).get("id").asLong();
        String token = obterToken("perfil@test.com", "senha123");

        MvcResult result = mockMvc.perform(get("/api/auth/{id}/profile", userId)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("email").asText()).isEqualTo("perfil@test.com");
        assertThat(body.has("password")).isFalse();
    }

    @Test
    void getProfile_semToken_retorna401() throws Exception {
        mockMvc.perform(get("/api/auth/1/profile"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deleteUser_autenticado_retorna200ERemoveUsuario() throws Exception {
        MvcResult registerResult = registrar("del@test.com", "senha123");
        long userId = objectMapper.readTree(registerResult.getResponse().getContentAsString()).get("id").asLong();
        String token = obterToken("del@test.com", "senha123");

        mockMvc.perform(delete("/api/auth/{id}", userId)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        assertThat(userRepository.existsById(userId)).isFalse();
    }
}
