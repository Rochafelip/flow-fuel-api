package com.devappmobile.flowfuel.vehicleshare;

import com.devappmobile.flowfuel.user.UserRepository;
import com.devappmobile.flowfuel.user.UserStatus;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class VehicleShareControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private VehicleRepository vehicleRepository;
    @Autowired
    private VehicleShareRepository vehicleShareRepository;
    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void limparBanco() {
        vehicleShareRepository.deleteAll();
        vehicleRepository.deleteAll();
        userRepository.deleteAll();
    }

    private String registrarEAtivar(String email) throws Exception {
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

    private Long criarVeiculo(String jwtDono) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/vehicles")
                .header("Authorization", "Bearer " + jwtDono)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"type":"CARRO","energyType":"COMBUSTION","currentKm":1000,"capacity":50,"brand":"Toyota","model":"Corolla"}
                        """))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    @Test
    void fluxoCompleto_convidarAceitarLancarEventoRevogar() throws Exception {
        String jwtDono = registrarEAtivar("share-owner@test.com");
        String jwtConvidado = registrarEAtivar("share-guest@test.com");
        Long vehicleId = criarVeiculo(jwtDono);

        // Dono convida o convidado
        MvcResult conviteResult = mockMvc.perform(post("/api/v1/vehicle-shares")
                .header("Authorization", "Bearer " + jwtDono)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"vehicleId":%d,"inviteeEmail":"share-guest@test.com","durationDays":3}
                        """.formatted(vehicleId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        Long shareId = objectMapper.readTree(conviteResult.getResponse().getContentAsString()).get("id").asLong();

        // Convidado vê o convite pendente
        mockMvc.perform(get("/api/v1/vehicle-shares/pending")
                .header("Authorization", "Bearer " + jwtConvidado))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(shareId));

        // Convidado aceita
        mockMvc.perform(post("/api/v1/vehicle-shares/{id}/accept", shareId)
                .header("Authorization", "Bearer " + jwtConvidado))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // Convidado vê o veículo em "compartilhados comigo"
        mockMvc.perform(get("/api/v1/vehicle-shares/active-for-me")
                .header("Authorization", "Bearer " + jwtConvidado))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].vehicleId").value(vehicleId.intValue()));

        // Convidado lança evento de categoria permitida (FUEL)
        mockMvc.perform(post("/api/v1/vehicle-events")
                .header("Authorization", "Bearer " + jwtConvidado)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"vehicleId":%d,"type":"FUEL","amount":150.00,"eventDate":"2026-07-14"}
                        """.formatted(vehicleId)))
                .andExpect(status().isOk());

        // Convidado tenta lançar categoria não permitida (INSURANCE) → 403
        mockMvc.perform(post("/api/v1/vehicle-events")
                .header("Authorization", "Bearer " + jwtConvidado)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"vehicleId":%d,"type":"INSURANCE","amount":500.00,"eventDate":"2026-07-14"}
                        """.formatted(vehicleId)))
                .andExpect(status().isForbidden());

        // Convidado atualiza o odômetro
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/vehicles/{id}/odometer?currentKm=1050", vehicleId)
                .header("Authorization", "Bearer " + jwtConvidado))
                .andExpect(status().isOk());

        // Dono revoga
        mockMvc.perform(delete("/api/v1/vehicle-shares/{id}", shareId)
                .header("Authorization", "Bearer " + jwtDono))
                .andExpect(status().isNoContent());

        // Convidado perde acesso
        mockMvc.perform(post("/api/v1/vehicle-events")
                .header("Authorization", "Bearer " + jwtConvidado)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"vehicleId":%d,"type":"FUEL","amount":100.00,"eventDate":"2026-07-14"}
                        """.formatted(vehicleId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_conviteDuplicadoEnquantoPendente_retorna409() throws Exception {
        String jwtDono = registrarEAtivar("share-dup-owner@test.com");
        registrarEAtivar("share-dup-guest@test.com");
        Long vehicleId = criarVeiculo(jwtDono);

        mockMvc.perform(post("/api/v1/vehicle-shares")
                .header("Authorization", "Bearer " + jwtDono)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"vehicleId":%d,"inviteeEmail":"share-dup-guest@test.com","durationDays":1}
                        """.formatted(vehicleId)));

        mockMvc.perform(post("/api/v1/vehicle-shares")
                .header("Authorization", "Bearer " + jwtDono)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"vehicleId":%d,"inviteeEmail":"share-dup-guest@test.com","durationDays":1}
                        """.formatted(vehicleId)))
                .andExpect(status().isConflict());
    }

    @Test
    void accept_usuarioQueNaoEhOConvidado_retorna403() throws Exception {
        String jwtDono = registrarEAtivar("share-forbid-owner@test.com");
        registrarEAtivar("share-forbid-guest@test.com");
        String jwtOutro = registrarEAtivar("share-forbid-outro@test.com");
        Long vehicleId = criarVeiculo(jwtDono);

        MvcResult conviteResult = mockMvc.perform(post("/api/v1/vehicle-shares")
                .header("Authorization", "Bearer " + jwtDono)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"vehicleId":%d,"inviteeEmail":"share-forbid-guest@test.com","durationDays":1}
                        """.formatted(vehicleId)))
                .andReturn();
        Long shareId = objectMapper.readTree(conviteResult.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(post("/api/v1/vehicle-shares/{id}/accept", shareId)
                .header("Authorization", "Bearer " + jwtOutro))
                .andExpect(status().isForbidden());
    }
}
