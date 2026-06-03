package com.devappmobile.flowfuel.vehicleevent;

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

import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class VehicleEventControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private VehicleRepository vehicleRepository;
    @Autowired private VehicleEventRepository vehicleEventRepository;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void limparBanco() {
        vehicleEventRepository.deleteAll();
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

    private long criarEvento(String token, long vehicleId, String type, String amount, String eventDate) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/vehicle-events")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "vehicleId": %d,
                          "type": "%s",
                          "amount": %s,
                          "eventDate": "%s",
                          "odometer": 50100,
                          "description": "evento"
                        }
                        """.formatted(vehicleId, type, amount, eventDate)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    // --- POST happy path ---

    @Test
    void createVehicleEvent_dadosValidos_persisteERetornaPayload() throws Exception {
        String token = obterToken("create@test.com");
        long vehicleId = criarVeiculo(token);

        MvcResult result = mockMvc.perform(post("/api/v1/vehicle-events")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "vehicleId": %d,
                          "type": "MAINTENANCE",
                          "amount": 250.50,
                          "eventDate": "2026-01-10",
                          "odometer": 50200,
                          "description": "Troca de óleo"
                        }
                        """.formatted(vehicleId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.vehicleId").value(vehicleId))
                .andExpect(jsonPath("$.type").value("MAINTENANCE"))
                .andExpect(jsonPath("$.amount").value(250.50))
                .andExpect(jsonPath("$.eventDate").value("2026-01-10"))
                .andExpect(jsonPath("$.odometer").value(50200))
                .andExpect(jsonPath("$.description").value("Troca de óleo"))
                .andReturn();

        long id = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
        assert vehicleEventRepository.findById(id).isPresent();
    }

    // --- POST veículo de outro usuário ---

    @Test
    void createVehicleEvent_veiculoDeOutroUsuario_retorna403() throws Exception {
        String tokenA = obterToken("forbA@test.com");
        String tokenB = obterToken("forbB@test.com");
        long vehicleIdA = criarVeiculo(tokenA);

        mockMvc.perform(post("/api/v1/vehicle-events")
                .header("Authorization", "Bearer " + tokenB)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "vehicleId": %d,
                          "type": "MAINTENANCE",
                          "amount": 250.00,
                          "eventDate": "2026-01-10"
                        }
                        """.formatted(vehicleIdA)))
                .andExpect(status().isForbidden());
    }

    // --- POST payload inválido ---

    @Test
    void createVehicleEvent_amountInvalido_retorna400ProblemJson() throws Exception {
        String token = obterToken("amount@test.com");
        long vehicleId = criarVeiculo(token);

        mockMvc.perform(post("/api/v1/vehicle-events")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "vehicleId": %d,
                          "type": "MAINTENANCE",
                          "amount": 0,
                          "eventDate": "2026-01-10"
                        }
                        """.formatted(vehicleId)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void createVehicleEvent_camposObrigatoriosAusentes_retorna400ProblemJson() throws Exception {
        String token = obterToken("required@test.com");
        long vehicleId = criarVeiculo(token);

        mockMvc.perform(post("/api/v1/vehicle-events")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "vehicleId": %d
                        }
                        """.formatted(vehicleId)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void createVehicleEvent_eventDateFutura_retorna400ProblemJson() throws Exception {
        String token = obterToken("future@test.com");
        long vehicleId = criarVeiculo(token);
        String futuro = LocalDate.now().plusYears(1).toString();

        mockMvc.perform(post("/api/v1/vehicle-events")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "vehicleId": %d,
                          "type": "MAINTENANCE",
                          "amount": 100.00,
                          "eventDate": "%s"
                        }
                        """.formatted(vehicleId, futuro)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    // --- GET listagem paginada ---

    @Test
    void getVehicleEvents_semFiltros_retornaTodos() throws Exception {
        String token = obterToken("list@test.com");
        long vehicleId = criarVeiculo(token);
        criarEvento(token, vehicleId, "MAINTENANCE", "100.00", "2026-01-10");
        criarEvento(token, vehicleId, "OIL_CHANGE", "200.00", "2026-02-15");

        mockMvc.perform(get("/api/v1/vehicle-events/vehicle/{id}", vehicleId)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void getVehicleEvents_filtroPorType_retornaApenasDoTipo() throws Exception {
        String token = obterToken("filtype@test.com");
        long vehicleId = criarVeiculo(token);
        criarEvento(token, vehicleId, "MAINTENANCE", "100.00", "2026-01-10");
        criarEvento(token, vehicleId, "OIL_CHANGE", "200.00", "2026-02-15");

        mockMvc.perform(get("/api/v1/vehicle-events/vehicle/{id}", vehicleId)
                .header("Authorization", "Bearer " + token)
                .param("type", "MAINTENANCE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].type").value("MAINTENANCE"));
    }

    @Test
    void getVehicleEvents_filtroPorPeriodo_retornaApenasDoIntervalo() throws Exception {
        String token = obterToken("filperiod@test.com");
        long vehicleId = criarVeiculo(token);
        criarEvento(token, vehicleId, "MAINTENANCE", "100.00", "2026-01-10");
        criarEvento(token, vehicleId, "OIL_CHANGE", "200.00", "2026-02-15");

        mockMvc.perform(get("/api/v1/vehicle-events/vehicle/{id}", vehicleId)
                .header("Authorization", "Bearer " + token)
                .param("startDate", "2026-02-01")
                .param("endDate", "2026-02-28"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].type").value("OIL_CHANGE"));
    }

    @Test
    void getVehicleEvents_filtrosCombinados_retornaIntersecao() throws Exception {
        String token = obterToken("filcomb@test.com");
        long vehicleId = criarVeiculo(token);
        criarEvento(token, vehicleId, "MAINTENANCE", "100.00", "2026-01-10");
        criarEvento(token, vehicleId, "MAINTENANCE", "150.00", "2026-02-20");
        criarEvento(token, vehicleId, "OIL_CHANGE", "200.00", "2026-02-15");

        mockMvc.perform(get("/api/v1/vehicle-events/vehicle/{id}", vehicleId)
                .header("Authorization", "Bearer " + token)
                .param("type", "MAINTENANCE")
                .param("startDate", "2026-02-01")
                .param("endDate", "2026-02-28"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].type").value("MAINTENANCE"))
                .andExpect(jsonPath("$.content[0].eventDate").value("2026-02-20"));
    }

    // --- Ordenação ---

    @Test
    void getVehicleEvents_ordenaPorEventDateDesc() throws Exception {
        String token = obterToken("ordemdata@test.com");
        long vehicleId = criarVeiculo(token);
        criarEvento(token, vehicleId, "MAINTENANCE", "100.00", "2026-01-10");
        criarEvento(token, vehicleId, "MAINTENANCE", "100.00", "2026-03-10");
        criarEvento(token, vehicleId, "MAINTENANCE", "100.00", "2026-02-10");

        mockMvc.perform(get("/api/v1/vehicle-events/vehicle/{id}", vehicleId)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].eventDate").value("2026-03-10"))
                .andExpect(jsonPath("$.content[1].eventDate").value("2026-02-10"))
                .andExpect(jsonPath("$.content[2].eventDate").value("2026-01-10"));
    }

    @Test
    void getVehicleEvents_mesmaData_ordenaPorCreatedAtEIdDesc() throws Exception {
        String token = obterToken("ordemcreated@test.com");
        long vehicleId = criarVeiculo(token);
        long id1 = criarEvento(token, vehicleId, "MAINTENANCE", "100.00", "2026-01-10");
        long id2 = criarEvento(token, vehicleId, "MAINTENANCE", "100.00", "2026-01-10");
        long id3 = criarEvento(token, vehicleId, "MAINTENANCE", "100.00", "2026-01-10");

        MvcResult result = mockMvc.perform(get("/api/v1/vehicle-events/vehicle/{id}", vehicleId)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3))
                .andReturn();

        JsonNode content = objectMapper.readTree(result.getResponse().getContentAsString()).get("content");
        assert content.get(0).get("id").asLong() == id3;
        assert content.get(1).get("id").asLong() == id2;
        assert content.get(2).get("id").asLong() == id1;
    }

    // --- PUT parcial ---

    @Test
    void updateVehicleEvent_apenasAmount_naoSobrescreveCamposOpcionaisAusentes() throws Exception {
        String token = obterToken("putamount@test.com");
        long vehicleId = criarVeiculo(token);
        long id = criarEvento(token, vehicleId, "MAINTENANCE", "100.00", "2026-01-10");

        mockMvc.perform(put("/api/v1/vehicle-events/{id}", id)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "vehicleId": %d,
                          "type": "MAINTENANCE",
                          "amount": 999.99,
                          "eventDate": "2026-01-10"
                        }
                        """.formatted(vehicleId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(999.99))
                .andExpect(jsonPath("$.type").value("MAINTENANCE"))
                .andExpect(jsonPath("$.eventDate").value("2026-01-10"))
                .andExpect(jsonPath("$.odometer").value(50100))
                .andExpect(jsonPath("$.description").value("evento"));
    }

    @Test
    void updateVehicleEvent_apenasDescription_naoSobrescreveCamposOpcionaisAusentes() throws Exception {
        String token = obterToken("putdesc@test.com");
        long vehicleId = criarVeiculo(token);
        long id = criarEvento(token, vehicleId, "MAINTENANCE", "100.00", "2026-01-10");

        mockMvc.perform(put("/api/v1/vehicle-events/{id}", id)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "vehicleId": %d,
                          "type": "MAINTENANCE",
                          "amount": 100.00,
                          "eventDate": "2026-01-10",
                          "description": "atualizada"
                        }
                        """.formatted(vehicleId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("atualizada"))
                .andExpect(jsonPath("$.amount").value(100.00))
                .andExpect(jsonPath("$.type").value("MAINTENANCE"))
                .andExpect(jsonPath("$.eventDate").value("2026-01-10"))
                .andExpect(jsonPath("$.odometer").value(50100));
    }

    // --- DELETE ---

    @Test
    void deleteVehicleEvent_donoCorreto_apagaERetorna404AoBuscar() throws Exception {
        String token = obterToken("delete@test.com");
        long vehicleId = criarVeiculo(token);
        long id = criarEvento(token, vehicleId, "MAINTENANCE", "100.00", "2026-01-10");

        mockMvc.perform(delete("/api/v1/vehicle-events/{id}", id)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/vehicle-events/{id}", id)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }
}
