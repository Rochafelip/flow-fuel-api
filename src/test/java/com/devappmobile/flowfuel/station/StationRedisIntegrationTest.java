package com.devappmobile.flowfuel.station;

import com.devappmobile.flowfuel.station.client.OpenChargeMapClient;
import com.devappmobile.flowfuel.station.client.OverpassClient;
import com.devappmobile.flowfuel.station.dto.StationResponseDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cobre StationService com Redis real (Testcontainers): cache hit evita
 * segunda chamada aos clients; rate limit por userId bloqueia apos 10
 * chamadas no mesmo minuto. OverpassClient/OpenChargeMapClient sao mockados
 * para nao depender de rede externa real no CI.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = "flowfuel.rate-limit.enabled=true")
class StationRedisIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisUrl(DynamicPropertyRegistry registry) {
        registry.add("flowfuel.rate-limit.redis-url",
                () -> "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
    }

    @Autowired private StationService stationService;
    @MockBean private OverpassClient overpassClient;
    @MockBean private OpenChargeMapClient openChargeMapClient;

    @BeforeEach
    void setUp() {
        when(overpassClient.findFuelStations(anyDouble(), anyDouble(), anyInt())).thenReturn(List.of(
                StationResponseDTO.builder().placeId("osm:node/1").name("Posto")
                        .type(StationType.FUEL).latitude(-8.05).longitude(-34.90).build()));
        when(openChargeMapClient.findChargingStations(anyDouble(), anyDouble(), anyInt()))
                .thenReturn(List.of());
    }

    @Test
    void findNearby_segundaChamadaMesmoLatLng_usaCacheNaoChamaClients() {
        stationService.findNearby(9001L, -8.111, -34.222, 5000);
        stationService.findNearby(9001L, -8.111, -34.222, 5000);

        verify(overpassClient, times(1)).findFuelStations(anyDouble(), anyDouble(), anyInt());
        verify(openChargeMapClient, times(1)).findChargingStations(anyDouble(), anyDouble(), anyInt());
    }

    @Test
    void findNearby_11aChamadaMesmoUsuarioNoMesmoMinuto_lancaRateLimitExceeded() {
        Long userId = 9002L;
        for (int i = 0; i < 10; i++) {
            stationService.findNearby(userId, -8.0 - (i * 0.01), -34.0, 5000);
        }

        assertThatThrownBy(() -> stationService.findNearby(userId, -8.5, -34.0, 5000))
                .isInstanceOf(com.devappmobile.flowfuel.exception.RateLimitExceededException.class);
    }
}
