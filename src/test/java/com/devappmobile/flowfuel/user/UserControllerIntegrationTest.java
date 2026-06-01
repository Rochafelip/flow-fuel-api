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
        return mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s","name":"Teste"}
                        """.formatted(email, password)))
                .andReturn();
    }

    private String obterToken(String email, String password) throws Exception {
        registrar(email, password);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s"}
                        """.formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    @Test
    void register_comDadosValidos_retorna200ECorpoSemSenha() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"novo@test.com","password":"senha123","name":"Novo"}
                        """))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        // register agora ja devolve usuario + par de tokens (login automatico)
        JsonNode user = body.get("user");
        assertThat(user.get("id").asLong()).isPositive();
        assertThat(user.get("email").asText()).isEqualTo("novo@test.com");
        assertThat(user.has("password")).isFalse();
        assertThat(body.get("accessToken").asText()).isNotBlank();
        assertThat(body.get("refreshToken").asText()).isNotBlank();
        assertThat(body.get("expiresIn").asLong()).isPositive();
    }

    @Test
    void register_comEmailDuplicado_retorna409() throws Exception {
        registrar("dup@test.com", "senha123");

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"dup@test.com","password":"outrasenha"}
                        """))
                .andExpect(status().isConflict());
    }

    @Test
    void register_comSenhaCurta_retorna400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"curta@test.com","password":"abc"}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_comCredenciaisValidas_retornaTokenPair() throws Exception {
        registrar("login@test.com", "senha123");

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"login@test.com","password":"senha123"}
                        """))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("accessToken").asText()).isNotBlank();
        assertThat(body.get("refreshToken").asText()).isNotBlank();
        assertThat(body.get("expiresIn").asLong()).isPositive();
    }

    @Test
    void refresh_comTokenValido_rotacionaParEoAntigoFicaInvalido() throws Exception {
        registrar("refresh@test.com", "senha123");

        JsonNode initial = objectMapper.readTree(
                mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"refresh@test.com","password":"senha123"}
                                """))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());

        String originalRefresh = initial.get("refreshToken").asText();

        // Primeiro refresh: deve emitir um novo par.
        JsonNode rotated = objectMapper.readTree(
                mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}
                                """.formatted(originalRefresh)))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());

        assertThat(rotated.get("accessToken").asText()).isNotBlank();
        assertThat(rotated.get("refreshToken").asText())
                .isNotBlank()
                .isNotEqualTo(originalRefresh);

        // Reutilizar o refresh antigo deve disparar 401 (cadeia revogada).
        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"refreshToken":"%s"}
                        """.formatted(originalRefresh)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_comTokenDesconhecido_retorna401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"refreshToken":"token-que-nunca-existiu"}
                        """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changePassword_comSenhaAtualCorreta_204ERevogaRefresh() throws Exception {
        registrar("trocasenha@test.com", "senha123");
        JsonNode pair = objectMapper.readTree(
                mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"trocasenha@test.com","password":"senha123"}
                                """))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());
        String access = pair.get("accessToken").asText();
        String refresh = pair.get("refreshToken").asText();
        long userId = pair.get("expiresIn").asLong() > 0 ? extractUserId(access) : 0;

        mockMvc.perform(put("/api/v1/auth/" + userId + "/password")
                .header("Authorization", "Bearer " + access)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"currentPassword":"senha123","newPassword":"novaSenha123"}
                        """))
                .andExpect(status().isNoContent());

        // Refresh anterior deve ter sido invalidado.
        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"refreshToken":"%s"}
                        """.formatted(refresh)))
                .andExpect(status().isUnauthorized());

        // Login com senha antiga falha.
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"trocasenha@test.com","password":"senha123"}
                        """))
                .andExpect(status().isUnauthorized());

        // Login com senha nova funciona.
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"trocasenha@test.com","password":"novaSenha123"}
                        """))
                .andExpect(status().isOk());
    }

    @Test
    void changePassword_comSenhaAtualErrada_401() throws Exception {
        registrar("senhaerrada@test.com", "senha123");
        JsonNode pair = objectMapper.readTree(
                mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"senhaerrada@test.com","password":"senha123"}
                                """))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());
        String access = pair.get("accessToken").asText();
        long userId = extractUserId(access);

        mockMvc.perform(put("/api/v1/auth/" + userId + "/password")
                .header("Authorization", "Bearer " + access)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"currentPassword":"errada","newPassword":"novaSenha123"}
                        """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changePassword_paraOutroUsuario_403() throws Exception {
        registrar("a@test.com", "senha123");
        String accessA = objectMapper.readTree(
                mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"a@test.com","password":"senha123"}
                                """))
                        .andReturn().getResponse().getContentAsString())
                .get("accessToken").asText();

        registrar("vitima@test.com", "senha123");
        String accessVitima = objectMapper.readTree(
                mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"vitima@test.com","password":"senha123"}
                                """))
                        .andReturn().getResponse().getContentAsString())
                .get("accessToken").asText();
        long vitimaId = extractUserId(accessVitima);

        mockMvc.perform(put("/api/v1/auth/" + vitimaId + "/password")
                .header("Authorization", "Bearer " + accessA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"currentPassword":"senha123","newPassword":"novaSenha123"}
                        """))
                .andExpect(status().isForbidden());
    }

    private long extractUserId(String accessToken) {
        // O JWT carrega userId no payload (claim).
        String payload = new String(java.util.Base64.getUrlDecoder()
                .decode(accessToken.split("\\.")[1]));
        try {
            return objectMapper.readTree(payload).get("userId").asLong();
        } catch (Exception e) {
            throw new IllegalStateException("token inesperado", e);
        }
    }

    @Test
    void logout_invalidaORefreshToken() throws Exception {
        registrar("logout@test.com", "senha123");
        JsonNode pair = objectMapper.readTree(
                mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"logout@test.com","password":"senha123"}
                                """))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());
        String access = pair.get("accessToken").asText();
        String refresh = pair.get("refreshToken").asText();

        mockMvc.perform(post("/api/v1/auth/logout")
                .header("Authorization", "Bearer " + access)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"refreshToken":"%s"}
                        """.formatted(refresh)))
                .andExpect(status().isNoContent());

        // Apos logout, o refresh nao deve ser aceito.
        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"refreshToken":"%s"}
                        """.formatted(refresh)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_comSenhaErrada_retorna401() throws Exception {
        registrar("errada@test.com", "senha123");

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"errada@test.com","password":"senhaerrada"}
                        """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getProfile_autenticado_retornaPerfilSemSenha() throws Exception {
        MvcResult registerResult = registrar("perfil@test.com", "senha123");
        long userId = objectMapper.readTree(registerResult.getResponse().getContentAsString()).get("user").get("id").asLong();
        String token = obterToken("perfil@test.com", "senha123");

        MvcResult result = mockMvc.perform(get("/api/v1/auth/{id}/profile", userId)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("email").asText()).isEqualTo("perfil@test.com");
        assertThat(body.has("password")).isFalse();
    }

    @Test
    void getProfile_semToken_retorna401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/1/profile"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getProfile_deOutroUsuario_retorna403() throws Exception {
        MvcResult registerA = registrar("a@test.com", "senha123");
        long userIdA = objectMapper.readTree(registerA.getResponse().getContentAsString()).get("user").get("id").asLong();
        String tokenB = obterToken("b@test.com", "senha123");

        mockMvc.perform(get("/api/v1/auth/{id}/profile", userIdA)
                .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateProfile_deOutroUsuario_retorna403() throws Exception {
        MvcResult registerA = registrar("a2@test.com", "senha123");
        long userIdA = objectMapper.readTree(registerA.getResponse().getContentAsString()).get("user").get("id").asLong();
        String tokenB = obterToken("b2@test.com", "senha123");

        mockMvc.perform(put("/api/v1/auth/{id}/profile", userIdA)
                .header("Authorization", "Bearer " + tokenB)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Hacker"}
                        """))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteUser_deOutroUsuario_retorna403ENaoRemove() throws Exception {
        MvcResult registerA = registrar("a3@test.com", "senha123");
        long userIdA = objectMapper.readTree(registerA.getResponse().getContentAsString()).get("user").get("id").asLong();
        String tokenB = obterToken("b3@test.com", "senha123");

        mockMvc.perform(delete("/api/v1/auth/{id}", userIdA)
                .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isForbidden());

        assertThat(userRepository.existsById(userIdA)).isTrue();
    }

    @Test
    void uploadProfilePicture_deOutroUsuario_retorna403() throws Exception {
        MvcResult registerA = registrar("a4@test.com", "senha123");
        long userIdA = objectMapper.readTree(registerA.getResponse().getContentAsString()).get("user").get("id").asLong();
        String tokenB = obterToken("b4@test.com", "senha123");

        mockMvc.perform(multipart("/api/v1/auth/{id}/upload-profile-picture", userIdA)
                .file(new org.springframework.mock.web.MockMultipartFile(
                        "file", "x.png", "image/png", new byte[] { 1, 2, 3 }))
                .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteUser_autenticado_retorna200ERemoveUsuario() throws Exception {
        MvcResult registerResult = registrar("del@test.com", "senha123");
        long userId = objectMapper.readTree(registerResult.getResponse().getContentAsString()).get("user").get("id").asLong();
        String token = obterToken("del@test.com", "senha123");

        mockMvc.perform(delete("/api/v1/auth/{id}", userId)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        assertThat(userRepository.existsById(userId)).isFalse();
    }

    // --- forgot / reset password ---
    // No perfil dev (expose-token=true) o token de reset volta no corpo da resposta.

    private String solicitarReset(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s"}
                        """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.has("resetToken") ? body.get("resetToken").asText() : null;
    }

    @Test
    void forgotPassword_emailExistente_geraTokenEPermiteRedefinir() throws Exception {
        registrar("forgot@test.com", "senha123");

        String resetToken = solicitarReset("forgot@test.com");
        assertThat(resetToken).isNotBlank();

        mockMvc.perform(post("/api/v1/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token":"%s","newPassword":"novaSenha456"}
                        """.formatted(resetToken)))
                .andExpect(status().isNoContent());

        // Senha antiga deixa de funcionar; a nova funciona.
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"forgot@test.com","password":"senha123"}
                        """))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"forgot@test.com","password":"novaSenha456"}
                        """))
                .andExpect(status().isOk());
    }

    @Test
    void forgotPassword_emailInexistente_retorna200SemToken() throws Exception {
        // Anti-enumeracao: mesma resposta (200) e nenhum token gerado.
        String resetToken = solicitarReset("naoexiste@test.com");
        assertThat(resetToken).isNull();
    }

    @Test
    void resetPassword_tokenInvalido_retorna401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token":"token-que-nunca-existiu","newPassword":"novaSenha456"}
                        """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void resetPassword_tokenReutilizado_retorna401() throws Exception {
        registrar("reuse@test.com", "senha123");
        String resetToken = solicitarReset("reuse@test.com");

        mockMvc.perform(post("/api/v1/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token":"%s","newPassword":"novaSenha456"}
                        """.formatted(resetToken)))
                .andExpect(status().isNoContent());

        // Token de uso unico: segunda tentativa falha.
        mockMvc.perform(post("/api/v1/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token":"%s","newPassword":"outraSenha789"}
                        """.formatted(resetToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void resetPassword_revogaRefreshTokensAtivos() throws Exception {
        registrar("resetrefresh@test.com", "senha123");
        String refresh = objectMapper.readTree(
                mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"resetrefresh@test.com","password":"senha123"}
                                """))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString())
                .get("refreshToken").asText();

        String resetToken = solicitarReset("resetrefresh@test.com");
        mockMvc.perform(post("/api/v1/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token":"%s","newPassword":"novaSenha456"}
                        """.formatted(resetToken)))
                .andExpect(status().isNoContent());

        // O refresh emitido antes do reset deve ter sido revogado.
        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"refreshToken":"%s"}
                        """.formatted(refresh)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void forgotPassword_novaSolicitacaoInvalidaTokenAnterior() throws Exception {
        registrar("doisreset@test.com", "senha123");

        String primeiroToken = solicitarReset("doisreset@test.com");
        String segundoToken = solicitarReset("doisreset@test.com");
        assertThat(segundoToken).isNotEqualTo(primeiroToken);

        // O primeiro token foi invalidado pela nova solicitacao.
        mockMvc.perform(post("/api/v1/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token":"%s","newPassword":"novaSenha456"}
                        """.formatted(primeiroToken)))
                .andExpect(status().isUnauthorized());

        // O segundo token ainda funciona.
        mockMvc.perform(post("/api/v1/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token":"%s","newPassword":"novaSenha456"}
                        """.formatted(segundoToken)))
                .andExpect(status().isNoContent());
    }
}
