package com.devappmobile.flowfuel.devicetoken;

import com.devappmobile.flowfuel.user.UserRepository;
import com.devappmobile.flowfuel.user.UserStatus;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DeviceTokenControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DeviceTokenRepository deviceTokenRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void limparBanco() {
        deviceTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    private String obterToken(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"senha123","name":"User"}
                        """.formatted(email)));
        userRepository.findByEmail(email).ifPresent(u -> {
            u.setStatus(UserStatus.ACTIVE);
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
    void registerToken_semAutenticacao_retorna401() throws Exception {
        mockMvc.perform(post("/api/v1/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token":"fcm-token-1","platform":"ANDROID"}
                        """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registerToken_tokenValido_criaERetorna200() throws Exception {
        String jwt = obterToken("device-register@test.com");

        mockMvc.perform(post("/api/v1/devices")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token":"fcm-token-1","platform":"ANDROID"}
                        """))
                .andExpect(status().isOk());

        assertThat(deviceTokenRepository.findById("fcm-token-1")).isPresent();
    }

    @Test
    void registerToken_semTokenNoBody_retorna400() throws Exception {
        String jwt = obterToken("device-register-invalido@test.com");

        mockMvc.perform(post("/api/v1/devices")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"platform":"ANDROID"}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteToken_donoCorreto_retorna204ERemoveDoBanco() throws Exception {
        String jwt = obterToken("device-delete@test.com");
        mockMvc.perform(post("/api/v1/devices")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token":"fcm-token-2","platform":"ANDROID"}
                        """));

        mockMvc.perform(delete("/api/v1/devices/{token}", "fcm-token-2")
                .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isNoContent());

        assertThat(deviceTokenRepository.findById("fcm-token-2")).isEmpty();
    }

    @Test
    void deleteToken_donoDiferente_retorna403ENaoRemove() throws Exception {
        String tokenA = obterToken("device-delete-userA@test.com");
        String tokenB = obterToken("device-delete-userB@test.com");
        mockMvc.perform(post("/api/v1/devices")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token":"fcm-token-3","platform":"ANDROID"}
                        """));

        mockMvc.perform(delete("/api/v1/devices/{token}", "fcm-token-3")
                .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isForbidden());

        assertThat(deviceTokenRepository.findById("fcm-token-3")).isPresent();
    }

    @Test
    void deleteToken_tokenInexistente_retorna204() throws Exception {
        String jwt = obterToken("device-delete-inexistente@test.com");

        mockMvc.perform(delete("/api/v1/devices/{token}", "token-que-nao-existe")
                .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isNoContent());
    }
}
