package com.devappmobile.flowfuel.station.client;

import com.devappmobile.flowfuel.exception.ExternalServiceUnavailableException;
import com.devappmobile.flowfuel.station.dto.GeocodeResultDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Arrays;
import java.util.List;

/**
 * Cliente do Nominatim (geocodificador OpenStreetMap) para resolver texto
 * livre (bairro/cidade) em coordenadas. Publica, sem key, mas com politica
 * de uso restritiva — ver checkGeocodeGlobalRateLimit em StationService
 * (limite global, nao so por usuario).
 * Ver docs/superpowers/specs/2026-08-14-station-geocode-design.md.
 */
@Component
public class NominatimClient {

    private static final Logger log = LoggerFactory.getLogger(NominatimClient.class);
    private static final String USER_AGENT = "FlowFuel/1.0 (+https://flowfuel-api.fly.dev)";

    private final RestClient restClient;
    private final String baseUrl;

    public NominatimClient(RestClient.Builder builder,
            @Value("${flowfuel.station.nominatim.base-url:https://nominatim.openstreetmap.org/search}") String baseUrl) {
        this.baseUrl = baseUrl;
        this.restClient = builder.build();
    }

    public List<GeocodeResultDTO> search(String query) {
        try {
            NominatimResultDTO[] response = restClient.get()
                    .uri(baseUrl + "?q={query}&format=jsonv2&addressdetails=0&countrycodes=br&limit=5", query)
                    .headers(headers -> headers.set("User-Agent", USER_AGENT))
                    .retrieve()
                    .body(NominatimResultDTO[].class);
            if (response == null) {
                return List.of();
            }
            return Arrays.stream(response)
                    .map(r -> GeocodeResultDTO.builder()
                            .displayName(r.getDisplayName())
                            .latitude(Double.parseDouble(r.getLat()))
                            .longitude(Double.parseDouble(r.getLon()))
                            .build())
                    .toList();
        } catch (RestClientException | NumberFormatException e) {
            log.warn("Falha ao chamar Nominatim: {}", e.getMessage());
            throw new ExternalServiceUnavailableException("Falha ao consultar Nominatim", e);
        }
    }
}
