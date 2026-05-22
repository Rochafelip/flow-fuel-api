package com.devappmobile.flowfuel.vehicle;

import com.devappmobile.flowfuel.refuel.RefuelRepository;
import com.devappmobile.flowfuel.user.UserRepository;
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

    @BeforeEach
    void limparBanco() {
        refuelRepository.deleteAll();
        vehicleRepository.deleteAll();
        userRepository.deleteAll();
    }

    private String obterToken(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"senha123","name":"User"}
                        """.formatted(email)));
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
}
