package com.devappmobile.flowfuel.station.client.google;

import com.devappmobile.flowfuel.exception.ExternalServiceUnavailableException;
import com.devappmobile.flowfuel.station.StationType;
import com.devappmobile.flowfuel.station.dto.StationResponseDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GooglePlacesClientTest {

    private GooglePlacesClient clientWithoutKey;
    private GooglePlacesClient clientWithKey;

    @BeforeEach
    void setUp() {
        clientWithoutKey = new GooglePlacesClient(
                RestClient.builder(), "https://places.googleapis.com", "");
        clientWithKey = new GooglePlacesClient(
                RestClient.builder(), "https://places.googleapis.com", "test-key");
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
