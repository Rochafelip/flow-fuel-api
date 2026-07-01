package com.devappmobile.flowfuel.station.client;

import com.devappmobile.flowfuel.exception.ExternalServiceUnavailableException;
import com.devappmobile.flowfuel.station.StationType;
import com.devappmobile.flowfuel.station.dto.StationResponseDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Cliente da Open Charge Map API para busca de estacoes de recarga eletrica.
 * Header X-API-Key e opcional: funciona sem ela, com rate limit mais baixo.
 * Ver docs/superpowers/specs/2026-07-01-postos-proximos-backend-design.md.
 */
@Component
public class OpenChargeMapClient {

    private static final Logger log = LoggerFactory.getLogger(OpenChargeMapClient.class);

    private final RestClient restClient;
    private final String baseUrl;
    private final String apiKey;

    public OpenChargeMapClient(RestClient.Builder builder,
            @Value("${flowfuel.station.open-charge-map.base-url:https://api.openchargemap.io/v3/poi}") String baseUrl,
            @Value("${flowfuel.station.open-charge-map.api-key:}") String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.restClient = builder.build();
    }

    public List<StationResponseDTO> findChargingStations(double lat, double lng, int radiusMeters) {
        double radiusKm = radiusMeters / 1000.0;
        try {
            OpenChargeMapPoiDTO[] response = restClient.get()
                    .uri(baseUrl + "?latitude={lat}&longitude={lng}&distance={distance}&distanceunit=KM&maxresults=20",
                            lat, lng, radiusKm)
                    .headers(headers -> {
                        if (!apiKey.isBlank()) {
                            headers.set("X-API-Key", apiKey);
                        }
                    })
                    .retrieve()
                    .body(OpenChargeMapPoiDTO[].class);
            if (response == null) {
                return List.of();
            }
            return Arrays.stream(response)
                    .map(this::toStationResponse)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (RestClientException e) {
            log.warn("Falha ao chamar Open Charge Map API: {}", e.getMessage());
            throw new ExternalServiceUnavailableException("Falha ao consultar Open Charge Map API", e);
        }
    }

    private StationResponseDTO toStationResponse(OpenChargeMapPoiDTO poi) {
        if (poi.getAddressInfo() == null
                || poi.getAddressInfo().getLatitude() == null
                || poi.getAddressInfo().getLongitude() == null) {
            return null;
        }
        return StationResponseDTO.builder()
                .placeId("ocm:" + poi.getId())
                .name(poi.getAddressInfo().getTitle() != null
                        ? poi.getAddressInfo().getTitle() : "Estação de recarga")
                .type(StationType.ELECTRIC)
                .rating(null)
                .latitude(poi.getAddressInfo().getLatitude())
                .longitude(poi.getAddressInfo().getLongitude())
                .build();
    }
}
