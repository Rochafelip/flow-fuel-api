package com.devappmobile.flowfuel.dashboard;

import com.devappmobile.flowfuel.refuel.RefuelRepository;
import com.devappmobile.flowfuel.user.UserRepository;
import com.devappmobile.flowfuel.vehicle.VehicleRepository;
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
class DashboardControllerIntegrationTest {

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
                        {"type":"Carro","energyType":"COMBUSTION","currentKm":50000,"capacity":55}
                        """))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private void criarAbastecimento(String token, long vehicleId, int odometer) throws Exception {
        mockMvc.perform(post("/api/v1/refuels")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "vehicleId": %d,
                          "odometer": %d,
                          "energyAmount": 40.0,
                          "pricePerUnit": 5.89,
                          "fullTank": true
                        }
                        """.formatted(vehicleId, odometer)))
                .andExpect(status().isOk());
    }

    @Test
    void getDashboard_semToken_retorna401() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/vehicle/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getDashboard_veiculoDoProprioUsuario_retornaMetricas() throws Exception {
        String token = obterToken("dash@test.com");
        long vehicleId = criarVeiculo(token);
        criarAbastecimento(token, vehicleId, 50500);

        mockMvc.perform(get("/api/v1/dashboard/vehicle/{id}", vehicleId)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vehicleId").value(vehicleId))
                .andExpect(jsonPath("$.totalRefuels").value(1))
                .andExpect(jsonPath("$.totalSpent").isNumber())
                .andExpect(jsonPath("$.lastOdometer").value(50500));
    }

    @Test
    void getDashboard_veiculoDeOutroUsuario_retorna403() throws Exception {
        String tokenA = obterToken("dashA@test.com");
        String tokenB = obterToken("dashB@test.com");
        long vehicleIdA = criarVeiculo(tokenA);

        mockMvc.perform(get("/api/v1/dashboard/vehicle/{id}", vehicleIdA)
                .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isForbidden());
    }

    @Test
    void getDashboard_comDoisAbastecimentosCheios_retornaConsumoMedio() throws Exception {
        String token = obterToken("consumo@test.com");
        long vehicleId = criarVeiculo(token);
        criarAbastecimento(token, vehicleId, 50500);
        criarAbastecimento(token, vehicleId, 51000);

        mockMvc.perform(get("/api/v1/dashboard/vehicle/{id}", vehicleId)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRefuels").value(2))
                .andExpect(jsonPath("$.averageConsumption").isNumber());
    }

    @Test
    void getDashboard_veiculoInexistente_retorna404() throws Exception {
        String token = obterToken("404dash@test.com");

        mockMvc.perform(get("/api/v1/dashboard/vehicle/99999")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }
}
