package com.devappmobile.flowfuel.station.client.google;

import com.devappmobile.flowfuel.exception.ExternalServiceUnavailableException;
import com.devappmobile.flowfuel.station.StationType;
import com.devappmobile.flowfuel.station.dto.StationResponseDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GooglePlacesClientTest {

    private GooglePlacesClient clientWithoutKey;
    private GooglePlacesClient clientWithKey;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        clientWithoutKey = new GooglePlacesClient(
                "https://places.googleapis.com", "", objectMapper);
        clientWithKey = new GooglePlacesClient(
                "https://places.googleapis.com", "test-key", objectMapper);
    }

    @Test
    void searchNearby_withEmptyKey_throwsException() {
        assertThatThrownBy(() -> clientWithoutKey.searchNearby(-23.5505, -46.6333, 1000))
                .isInstanceOf(ExternalServiceUnavailableException.class)
                .hasMessageContaining("GOOGLE_PLACES_API_KEY não configurada");
    }

    @Test
    void searchNearby_withValidKey_doesNotThrowOnConstruction() {
        assertThat(clientWithKey).isNotNull();
    }
}
