package com.devappmobile.flowfuel.vehicle;

import com.devappmobile.flowfuel.refuel.RefuelRepository;
import com.devappmobile.flowfuel.user.UserRepository;
import com.devappmobile.flowfuel.user.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import com.devappmobile.flowfuel.storage.StorageService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class VehicleControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private VehicleRepository vehicleRepository;
    @Autowired private RefuelRepository refuelRepository;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private StorageService storageService;

    @BeforeEach
    void limparBanco() {
        refuelRepository.deleteAll();
        vehicleRepository.deleteAll();
        userRepository.deleteAll();

        reset(storageService);
        when(storageService.upload(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(storageService.publicUrl(any()))
                .thenAnswer(inv -> "https://pub-test.r2.dev/" + inv.getArgument(0, String.class));
    }

    private String obterToken(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"senha123","name":"User"}
                        """.formatted(email)));
        // ativa a conta recem-criada (login bloqueado enquanto PENDING_ACTIVATION)
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

    private long criarVeiculo(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/vehicles")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "type": "Carro",
                          "energyType": "COMBUSTION",
                          "currentKm": 50000,
                          "capacity": 55,
                          "brand": "VW",
                          "model": "Gol"
                        }
                        """))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    @Test
    void createVehicle_autenticado_retornaVeiculoCriado() throws Exception {
        String token = obterToken("veiculo@test.com");

        MvcResult result = mockMvc.perform(post("/api/v1/vehicles")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "type": "Moto",
                          "energyType": "COMBUSTION",
                          "currentKm": 10000,
                          "capacity": 18
                        }
                        """))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assert body.get("id").asLong() > 0;
        assert body.get("type").asText().equals("Moto");
    }

    @Test
    void createVehicle_semToken_retorna401() throws Exception {
        mockMvc.perform(post("/api/v1/vehicles")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"type":"Carro","energyType":"COMBUSTION","currentKm":0,"capacity":50}
                        """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getUserVehicles_retornaListaDoUsuario() throws Exception {
        String token = obterToken("lista@test.com");
        criarVeiculo(token);

        mockMvc.perform(get("/api/v1/vehicles")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void getVehicleById_outroDono_retorna403() throws Exception {
        String tokenA = obterToken("userA@test.com");
        String tokenB = obterToken("userB@test.com");
        long vehicleId = criarVeiculo(tokenA);

        mockMvc.perform(get("/api/v1/vehicles/{id}", vehicleId)
                .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateOdometer_valorValido_retorna200() throws Exception {
        String token = obterToken("odometro@test.com");
        long vehicleId = criarVeiculo(token);

        mockMvc.perform(put("/api/v1/vehicles/{id}/odometer", vehicleId)
                .header("Authorization", "Bearer " + token)
                .param("currentKm", "55000"))
                .andExpect(status().isOk());
    }

    @Test
    void updateOdometer_valorMenorQueAtual_retorna400() throws Exception {
        String token = obterToken("odometrobaixo@test.com");
        long vehicleId = criarVeiculo(token);

        mockMvc.perform(put("/api/v1/vehicles/{id}/odometer", vehicleId)
                .header("Authorization", "Bearer " + token)
                .param("currentKm", "100"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteVehicle_donoCorreto_retorna200() throws Exception {
        String token = obterToken("del@test.com");
        long vehicleId = criarVeiculo(token);

        mockMvc.perform(delete("/api/v1/vehicles/{id}", vehicleId)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void deleteVehicle_comReabastecimentosAssociados_removeReabastecimentos() throws Exception {
        String token = obterToken("delcascade@test.com");
        long vehicleId = criarVeiculo(token);
        criarReabastecimento(token, vehicleId);
        criarReabastecimento(token, vehicleId);

        mockMvc.perform(delete("/api/v1/vehicles/{id}", vehicleId)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        assertThat(refuelRepository.findByVehicleIdOrderByRefuelDateDesc(vehicleId)).isEmpty();
    }

    private long criarReabastecimento(String token, long vehicleId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/refuels")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "vehicleId": %d,
                          "odometer": 50100,
                          "energyAmount": 40.0,
                          "pricePerUnit": 5.89,
                          "fullTank": true
                        }
                        """.formatted(vehicleId)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    @Test
    void setActiveVehicle_comDoisVeiculos_apenasUmFicaAtivo() throws Exception {
        String token = obterToken("ativo@test.com");
        criarVeiculo(token);
        long v2 = criarVeiculo(token);

        mockMvc.perform(put("/api/v1/vehicles/{id}/active", v2)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/vehicles/active")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(v2));
    }

    private static byte[] imagemJpegValida() throws Exception {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(10, 10,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    @Test
    void uploadPhoto_imagemValida_retorna200ComInternalUrl() throws Exception {
        String token = obterToken("foto-ok@test.com");
        long vehicleId = criarVeiculo(token);

        MockMultipartFile file = new MockMultipartFile("file", "foto.jpg", "image/jpeg", imagemJpegValida());

        mockMvc.perform(multipart("/api/v1/vehicles/{id}/photo", vehicleId)
                .file(file)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.internalUrl").value("/vehicles/" + vehicleId + "/photo"));
    }

    @Test
    void uploadPhoto_tipoInvalido_retorna400() throws Exception {
        String token = obterToken("foto-tipo-invalido@test.com");
        long vehicleId = criarVeiculo(token);

        MockMultipartFile file = new MockMultipartFile("file", "foto.gif", "image/gif", new byte[100]);

        mockMvc.perform(multipart("/api/v1/vehicles/{id}/photo", vehicleId)
                .file(file)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATED"));
    }

    @Test
    void uploadPhoto_arquivoAcimaDoLimiteDoServlet_retorna400() throws Exception {
        String token = obterToken("foto-grande@test.com");
        long vehicleId = criarVeiculo(token);

        byte[] arquivoGrande = new byte[6 * 1024 * 1024]; // acima de spring.servlet.multipart.max-file-size (5MB)
        MockMultipartFile file = new MockMultipartFile("file", "foto.jpg", "image/jpeg", arquivoGrande);

        mockMvc.perform(multipart("/api/v1/vehicles/{id}/photo", vehicleId)
                .file(file)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATED"));
    }

    @Test
    void uploadPhoto_donoDiferente_retorna403() throws Exception {
        String tokenA = obterToken("foto-userA@test.com");
        String tokenB = obterToken("foto-userB@test.com");
        long vehicleId = criarVeiculo(tokenA);

        MockMultipartFile file = new MockMultipartFile("file", "foto.jpg", "image/jpeg", new byte[100]);

        mockMvc.perform(multipart("/api/v1/vehicles/{id}/photo", vehicleId)
                .file(file)
                .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isForbidden());
    }

    @Test
    void uploadPhoto_veiculoInexistente_retorna404() throws Exception {
        String token = obterToken("foto-404@test.com");

        MockMultipartFile file = new MockMultipartFile("file", "foto.jpg", "image/jpeg", new byte[100]);

        mockMvc.perform(multipart("/api/v1/vehicles/{id}/photo", 999999L)
                .file(file)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void getVehicleById_aposUploadDeFoto_retornaPhotoComoInternalUrl() throws Exception {
        String token = obterToken("foto-get@test.com");
        long vehicleId = criarVeiculo(token);

        MockMultipartFile file = new MockMultipartFile("file", "foto.jpg", "image/jpeg", imagemJpegValida());
        mockMvc.perform(multipart("/api/v1/vehicles/{id}/photo", vehicleId)
                .file(file)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/vehicles/{id}", vehicleId)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.photo").value("/vehicles/" + vehicleId + "/photo"));
    }

    @Test
    void getPhoto_semFotoUpada_retorna204() throws Exception {
        String token = obterToken("foto-get204@test.com");
        long vehicleId = criarVeiculo(token);

        mockMvc.perform(get("/api/v1/vehicles/{id}/photo", vehicleId)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    @Test
    void getPhoto_aposUpload_retorna302ComLocation() throws Exception {
        String token = obterToken("foto-get200@test.com");
        long vehicleId = criarVeiculo(token);

        MockMultipartFile file = new MockMultipartFile("file", "foto.jpg", "image/jpeg", imagemJpegValida());
        mockMvc.perform(multipart("/api/v1/vehicles/{id}/photo", vehicleId)
                .file(file)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/vehicles/{id}/photo", vehicleId)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        org.hamcrest.Matchers.startsWith("https://pub-test.r2.dev/vehicle_photos/" + vehicleId)));
    }

    @Test
    void getPhoto_donoDiferente_retorna403() throws Exception {
        String tokenA = obterToken("foto-get-userA@test.com");
        String tokenB = obterToken("foto-get-userB@test.com");
        long vehicleId = criarVeiculo(tokenA);

        mockMvc.perform(get("/api/v1/vehicles/{id}/photo", vehicleId)
                .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isForbidden());
    }

    @Test
    void getPhoto_veiculoInexistente_retorna404() throws Exception {
        String token = obterToken("foto-get404@test.com");

        mockMvc.perform(get("/api/v1/vehicles/{id}/photo", 999999L)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletePhoto_aposUpload_retorna204EGetSubsequenteRetorna204() throws Exception {
        String token = obterToken("foto-delete@test.com");
        long vehicleId = criarVeiculo(token);

        MockMultipartFile file = new MockMultipartFile("file", "foto.jpg", "image/jpeg", imagemJpegValida());
        mockMvc.perform(multipart("/api/v1/vehicles/{id}/photo", vehicleId)
                .file(file)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/vehicles/{id}/photo", vehicleId)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/vehicles/{id}/photo", vehicleId)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    @Test
    void deletePhoto_semFotoExistente_retorna204SemErro() throws Exception {
        String token = obterToken("foto-delete-noop@test.com");
        long vehicleId = criarVeiculo(token);

        mockMvc.perform(delete("/api/v1/vehicles/{id}/photo", vehicleId)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    @Test
    void deletePhoto_donoDiferente_retorna403() throws Exception {
        String tokenA = obterToken("foto-delete-userA@test.com");
        String tokenB = obterToken("foto-delete-userB@test.com");
        long vehicleId = criarVeiculo(tokenA);

        mockMvc.perform(delete("/api/v1/vehicles/{id}/photo", vehicleId)
                .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isForbidden());
    }

    @Test
    void deletePhoto_veiculoInexistente_retorna404() throws Exception {
        String token = obterToken("foto-delete404@test.com");

        mockMvc.perform(delete("/api/v1/vehicles/{id}/photo", 999999L)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }
}
