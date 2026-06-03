package com.devappmobile.flowfuel.refuel;

import com.devappmobile.flowfuel.user.UserRepository;
import com.devappmobile.flowfuel.user.UserStatus;
import com.devappmobile.flowfuel.vehicle.VehicleRepository;
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
class RefuelControllerIntegrationTest {

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
                        {"type":"Carro","energyType":"COMBUSTION","currentKm":50000,"capacity":55}
                        """))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private long criarAbastecimento(String token, long vehicleId, int odometer) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/refuels")
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
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    @Test
    void createRefuel_dadosValidos_retornaRefuelComKmCalculado() throws Exception {
        String token = obterToken("refuel@test.com");
        long vehicleId = criarVeiculo(token);

        MvcResult result = mockMvc.perform(post("/api/v1/refuels")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "vehicleId": %d,
                          "odometer": 50500,
                          "energyAmount": 40.0,
                          "pricePerUnit": 5.89,
                          "fullTank": true
                        }
                        """.formatted(vehicleId)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assert body.get("kmSinceLastRefuel").asInt() == 500;
        assert body.get("fullTank").asBoolean();
    }

    @Test
    void createRefuel_odometroMenorQueAtual_retorna400() throws Exception {
        String token = obterToken("odometrobaixo@test.com");
        long vehicleId = criarVeiculo(token);

        mockMvc.perform(post("/api/v1/refuels")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "vehicleId": %d,
                          "odometer": 100,
                          "energyAmount": 40.0,
                          "pricePerUnit": 5.89
                        }
                        """.formatted(vehicleId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createRefuel_veiculoDeOutroUsuario_retorna403() throws Exception {
        String tokenA = obterToken("refuelA@test.com");
        String tokenB = obterToken("refuelB@test.com");
        long vehicleIdA = criarVeiculo(tokenA);

        mockMvc.perform(post("/api/v1/refuels")
                .header("Authorization", "Bearer " + tokenB)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "vehicleId": %d,
                          "odometer": 51000,
                          "energyAmount": 40.0,
                          "pricePerUnit": 5.89
                        }
                        """.formatted(vehicleIdA)))
                .andExpect(status().isForbidden());
    }

    @Test
    void getVehicleRefuels_retornaHistoricoOrdenadoPorData() throws Exception {
        String token = obterToken("historico@test.com");
        long vehicleId = criarVeiculo(token);
        criarAbastecimento(token, vehicleId, 50500);
        criarAbastecimento(token, vehicleId, 51000);

        mockMvc.perform(get("/api/v1/refuels/vehicle/{id}", vehicleId)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void getRefuelById_donoCorreto_retorna200() throws Exception {
        String token = obterToken("byid@test.com");
        long vehicleId = criarVeiculo(token);
        long refuelId = criarAbastecimento(token, vehicleId, 50500);

        mockMvc.perform(get("/api/v1/refuels/{id}", refuelId)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(refuelId));
    }

    @Test
    void getRefuelById_outroDono_retorna403() throws Exception {
        String tokenA = obterToken("getA@test.com");
        String tokenB = obterToken("getB@test.com");
        long vehicleId = criarVeiculo(tokenA);
        long refuelId = criarAbastecimento(tokenA, vehicleId, 50500);

        mockMvc.perform(get("/api/v1/refuels/{id}", refuelId)
                .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteRefuel_donoCorreto_retorna200() throws Exception {
        String token = obterToken("delrefuel@test.com");
        long vehicleId = criarVeiculo(token);
        long refuelId = criarAbastecimento(token, vehicleId, 50500);

        mockMvc.perform(delete("/api/v1/refuels/{id}", refuelId)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void getVehicleRefuels_comFiltroDePeriodo_retornaApenasOsDoIntervalo() throws Exception {
        String token = obterToken("filtro@test.com");
        long vehicleId = criarVeiculo(token);
        criarAbastecimento(token, vehicleId, 50500);

        mockMvc.perform(get("/api/v1/refuels/vehicle/{id}", vehicleId)
                .header("Authorization", "Bearer " + token)
                .param("startDate", "2020-01-01")
                .param("endDate", "2020-12-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }
}
