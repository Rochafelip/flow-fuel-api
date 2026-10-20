package com.devappmobile.flowfuel.station;

import com.devappmobile.flowfuel.station.client.google.GooglePlacesClient;
import com.devappmobile.flowfuel.user.UserRepository;
import com.devappmobile.flowfuel.user.UserStatus;
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

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class StationControllerGoogleIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @MockBean private GooglePlacesClient googlePlacesClient;

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
        return result.getResponse().getContentAsString();
    }

    @Test
    void getNearbyStationsGoogle_withValidParams_returns200() throws Exception {
        when(googlePlacesClient.searchNearby(anyDouble(), anyDouble(), anyInt()))
                .thenReturn(List.of());

        String tokenResponse = obterToken("test@gmail.com");
        String token = tokenResponse.substring(tokenResponse.indexOf("\"accessToken\":\"") + 14,
                tokenResponse.indexOf("\"", tokenResponse.indexOf("\"accessToken\":\"") + 14));

        mockMvc.perform(get("/api/v1/stations/nearby-google")
                .param("lat", "-23.5505")
                .param("lng", "-46.6333")
                .param("radius", "1000")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void getNearbyStationsGoogle_withInvalidLat_returns400() throws Exception {
        String tokenResponse = obterToken("test2@gmail.com");
        String token = tokenResponse.substring(tokenResponse.indexOf("\"accessToken\":\"") + 14,
                tokenResponse.indexOf("\"", tokenResponse.indexOf("\"accessToken\":\"") + 14));

        mockMvc.perform(get("/api/v1/stations/nearby-google")
                .param("lat", "100")
                .param("lng", "-46.6333")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getNearbyStationsGoogle_withRadiusTooLarge_returns400() throws Exception {
        String tokenResponse = obterToken("test3@gmail.com");
        String token = tokenResponse.substring(tokenResponse.indexOf("\"accessToken\":\"") + 14,
                tokenResponse.indexOf("\"", tokenResponse.indexOf("\"accessToken\":\"") + 14));

        mockMvc.perform(get("/api/v1/stations/nearby-google")
                .param("lat", "-23.5505")
                .param("lng", "-46.6333")
                .param("radius", "60000")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }
}
