package com.devappmobile.flowfuel.station;

import com.devappmobile.flowfuel.station.dto.StationResponseDTO;
import com.devappmobile.flowfuel.user.UserRepository;
import com.devappmobile.flowfuel.user.UserStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class StationControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private StationService stationService;

    @BeforeEach
    void limparBanco() {
        userRepository.deleteAll();
    }

    private String obterToken(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"senha123","name":"Teste"}
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
    void getNearbyStations_semToken_retorna401() throws Exception {
        mockMvc.perform(get("/api/v1/stations/nearby").param("lat", "-8.05").param("lng", "-34.90"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getNearbyStations_semLatELng_retorna400() throws Exception {
        String token = obterToken("station-user1@test.com");
        mockMvc.perform(get("/api/v1/stations/nearby").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getNearbyStations_latForaDeFaixa_retorna400() throws Exception {
        String token = obterToken("station-user2@test.com");
        mockMvc.perform(get("/api/v1/stations/nearby")
                        .header("Authorization", "Bearer " + token)
                        .param("lat", "-999").param("lng", "-34.90"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getNearbyStations_requisicaoValida_retorna200ComResultadosDoService() throws Exception {
        String token = obterToken("station-user3@test.com");
        when(stationService.findNearby(anyLong(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(List.of(StationResponseDTO.builder()
                        .placeId("osm:node/1").name("Posto Teste")
                        .type(StationType.FUEL).distanceMeters(420)
                        .latitude(-8.05).longitude(-34.90).build()));

        mockMvc.perform(get("/api/v1/stations/nearby")
                        .header("Authorization", "Bearer " + token)
                        .param("lat", "-8.05").param("lng", "-34.90"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].placeId").value("osm:node/1"))
                .andExpect(jsonPath("$[0].type").value("FUEL"))
                .andExpect(jsonPath("$[0].distanceMeters").value(420))
                .andExpect(jsonPath("$[0].rating").doesNotExist());
    }
}
