package com.devappmobile.flowfuel.station;

import com.devappmobile.flowfuel.exception.ExternalServiceUnavailableException;
import com.devappmobile.flowfuel.exception.RateLimitExceededException;
import com.devappmobile.flowfuel.station.client.NominatimClient;
import com.devappmobile.flowfuel.station.client.OpenChargeMapClient;
import com.devappmobile.flowfuel.station.client.OverpassClient;
import com.devappmobile.flowfuel.station.dto.GeocodeResultDTO;
import com.devappmobile.flowfuel.station.dto.StationResponseDTO;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.distributed.proxy.RemoteBucketBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StationServiceTest {

    @Mock private OverpassClient overpassClient;
    @Mock private OpenChargeMapClient openChargeMapClient;
    @Mock private StationCacheService cacheService;
    @Mock private ObjectProvider<ProxyManager<String>> proxyManagerProvider;
    @Mock private ProxyManager<String> proxyManager;
    @Mock private RemoteBucketBuilder<String> bucketBuilder;
    @Mock private BucketProxy bucketProxy;
    @Mock private NominatimClient nominatimClient;
    @Mock private GeocodeCacheService geocodeCacheService;

    private StationService stationService;

    @BeforeEach
    void setUp() {
        stationService = new StationService(overpassClient, openChargeMapClient, cacheService,
                proxyManagerProvider, nominatimClient, geocodeCacheService);
    }

    private void stubRateLimitAllows() {
        when(proxyManagerProvider.getIfAvailable()).thenReturn(proxyManager);
        doReturn(bucketBuilder).when(proxyManager).builder();
        when(bucketBuilder.build(any(), any(Supplier.class))).thenReturn(bucketProxy);
        when(bucketProxy.tryConsumeAndReturnRemaining(1))
                .thenReturn(ConsumptionProbe.consumed(9, 0));
    }

    @Test
    void findNearby_cacheHit_naoChamaClientes() {
        List<StationResponseDTO> cached = List.of(
                StationResponseDTO.builder().placeId("osm:node/1").name("Posto")
                        .type(StationType.FUEL).distanceMeters(100)
                        .latitude(-8.05).longitude(-34.90).build());
        stubRateLimitAllows();
        when(cacheService.get(any())).thenReturn(Optional.of(cached));

        List<StationResponseDTO> result = stationService.findNearby(1L, -8.05, -34.90, 5000);

        assertThat(result).isEqualTo(cached);
        verifyNoInteractions(overpassClient, openChargeMapClient);
    }

    @Test
    void findNearby_cacheMiss_mescladOrdenaPorDistanciaESalvaCache() {
        stubRateLimitAllows();
        when(cacheService.get(any())).thenReturn(Optional.empty());
        when(overpassClient.findFuelStations(-8.05, -34.90, 5000)).thenReturn(List.of(
                StationResponseDTO.builder().placeId("osm:node/1").name("Longe")
                        .type(StationType.FUEL).latitude(-8.10).longitude(-34.95).build()));
        when(openChargeMapClient.findChargingStations(-8.05, -34.90, 5000)).thenReturn(List.of(
                StationResponseDTO.builder().placeId("ocm:1").name("Perto")
                        .type(StationType.ELECTRIC).latitude(-8.051).longitude(-34.901).build()));

        List<StationResponseDTO> result = stationService.findNearby(1L, -8.05, -34.90, 5000);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getPlaceId()).isEqualTo("ocm:1"); // mais perto primeiro
        assertThat(result.get(0).getDistanceMeters()).isNotNull();
        assertThat(result.get(1).getPlaceId()).isEqualTo("osm:node/1");
        verify(cacheService).put(any(), eq(result));
    }

    @Test
    void findNearby_overpassFalhaOpenChargeMapResponde_retorna200SoComOcm() {
        stubRateLimitAllows();
        when(cacheService.get(any())).thenReturn(Optional.empty());
        when(overpassClient.findFuelStations(anyDouble(), anyDouble(), anyInt()))
                .thenThrow(new ExternalServiceUnavailableException("Overpass indisponivel"));
        when(openChargeMapClient.findChargingStations(-8.05, -34.90, 5000)).thenReturn(List.of(
                StationResponseDTO.builder().placeId("ocm:1").name("Perto")
                        .type(StationType.ELECTRIC).latitude(-8.05).longitude(-34.90).build()));

        List<StationResponseDTO> result = stationService.findNearby(1L, -8.05, -34.90, 5000);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPlaceId()).isEqualTo("ocm:1");
    }

    @Test
    void findNearby_ambasFontesFalham_lancaExternalServiceUnavailable() {
        stubRateLimitAllows();
        when(cacheService.get(any())).thenReturn(Optional.empty());
        when(overpassClient.findFuelStations(anyDouble(), anyDouble(), anyInt()))
                .thenThrow(new ExternalServiceUnavailableException("Overpass indisponivel"));
        when(openChargeMapClient.findChargingStations(anyDouble(), anyDouble(), anyInt()))
                .thenThrow(new ExternalServiceUnavailableException("OCM indisponivel"));

        assertThatThrownBy(() -> stationService.findNearby(1L, -8.05, -34.90, 5000))
                .isInstanceOf(ExternalServiceUnavailableException.class);
    }

    @Test
    void findNearby_rateLimitEstourado_lancaRateLimitExceeded() {
        when(proxyManagerProvider.getIfAvailable()).thenReturn(proxyManager);
        doReturn(bucketBuilder).when(proxyManager).builder();
        when(bucketBuilder.build(any(), any(Supplier.class))).thenReturn(bucketProxy);
        when(bucketProxy.tryConsumeAndReturnRemaining(1))
                .thenReturn(ConsumptionProbe.rejected(0, 1_000_000_000L, 0));

        assertThatThrownBy(() -> stationService.findNearby(1L, -8.05, -34.90, 5000))
                .isInstanceOf(RateLimitExceededException.class);
        verifyNoInteractions(cacheService, overpassClient, openChargeMapClient);
    }

    @Test
    void findNearby_semProxyManagerDisponivel_failOpenSeguemFluxo() {
        when(proxyManagerProvider.getIfAvailable()).thenReturn(null);
        when(cacheService.get(any())).thenReturn(Optional.of(List.of()));

        List<StationResponseDTO> result = stationService.findNearby(1L, -8.05, -34.90, 5000);

        assertThat(result).isEmpty();
    }

    @Test
    void geocode_cacheHit_naoChamaNominatim() {
        List<GeocodeResultDTO> cached = List.of(GeocodeResultDTO.builder()
                .displayName("Boa Viagem, Recife, Pernambuco, Brasil")
                .latitude(-8.12).longitude(-34.90).build());
        stubRateLimitAllows();
        when(geocodeCacheService.get(any())).thenReturn(Optional.of(cached));

        List<GeocodeResultDTO> result = stationService.geocode(1L, "Boa Viagem, Recife");

        assertThat(result).isEqualTo(cached);
        verifyNoInteractions(nominatimClient);
    }

    @Test
    void geocode_cacheMiss_chamaNominatimESalvaCache() {
        stubRateLimitAllows();
        when(geocodeCacheService.get(any())).thenReturn(Optional.empty());
        List<GeocodeResultDTO> fromNominatim = List.of(GeocodeResultDTO.builder()
                .displayName("Boa Viagem, Recife, Pernambuco, Brasil")
                .latitude(-8.12).longitude(-34.90).build());
        when(nominatimClient.search("Boa Viagem, Recife")).thenReturn(fromNominatim);

        List<GeocodeResultDTO> result = stationService.geocode(1L, "Boa Viagem, Recife");

        assertThat(result).isEqualTo(fromNominatim);
        verify(geocodeCacheService).put(any(), eq(fromNominatim));
    }

    @Test
    void geocode_nominatimFalha_lancaExternalServiceUnavailable() {
        stubRateLimitAllows();
        when(geocodeCacheService.get(any())).thenReturn(Optional.empty());
        when(nominatimClient.search(any()))
                .thenThrow(new ExternalServiceUnavailableException("Nominatim indisponivel"));

        assertThatThrownBy(() -> stationService.geocode(1L, "Boa Viagem"))
                .isInstanceOf(ExternalServiceUnavailableException.class);
    }

    @Test
    void geocode_rateLimitPorUsuarioEstourado_lancaRateLimitExceeded() {
        when(proxyManagerProvider.getIfAvailable()).thenReturn(proxyManager);
        doReturn(bucketBuilder).when(proxyManager).builder();
        when(bucketBuilder.build(any(), any(Supplier.class))).thenReturn(bucketProxy);
        when(bucketProxy.tryConsumeAndReturnRemaining(1))
                .thenReturn(ConsumptionProbe.rejected(0, 1_000_000_000L, 0));

        assertThatThrownBy(() -> stationService.geocode(1L, "Boa Viagem"))
                .isInstanceOf(RateLimitExceededException.class);
        verifyNoInteractions(geocodeCacheService, nominatimClient);
    }

    @Test
    void geocode_rateLimitGlobalEstourado_lancaRateLimitExceeded() {
        // Primeira chamada (limite por usuario) passa; segunda (limite global) estoura.
        when(proxyManagerProvider.getIfAvailable()).thenReturn(proxyManager);
        doReturn(bucketBuilder).when(proxyManager).builder();
        when(bucketBuilder.build(any(), any(Supplier.class))).thenReturn(bucketProxy);
        when(bucketProxy.tryConsumeAndReturnRemaining(1))
                .thenReturn(ConsumptionProbe.consumed(9, 0))
                .thenReturn(ConsumptionProbe.rejected(0, 1_000_000_000L, 0));

        assertThatThrownBy(() -> stationService.geocode(1L, "Boa Viagem"))
                .isInstanceOf(RateLimitExceededException.class);
        verifyNoInteractions(geocodeCacheService, nominatimClient);
    }
}
