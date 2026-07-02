package com.devappmobile.flowfuel.station.client;

import com.devappmobile.flowfuel.exception.ExternalServiceUnavailableException;
import com.devappmobile.flowfuel.station.StationType;
import com.devappmobile.flowfuel.station.dto.StationResponseDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Cliente da Overpass API (OpenStreetMap) para busca de postos de combustivel.
 * Publica, sem autenticacao/key. Ver docs/superpowers/specs/2026-07-01-postos-proximos-backend-design.md.
 */
@Component
public class OverpassClient {

    private static final Logger log = LoggerFactory.getLogger(OverpassClient.class);

    private final RestClient restClient;
    private final String baseUrl;

    public OverpassClient(RestClient.Builder builder,
            @Value("${flowfuel.station.overpass.base-url:https://overpass-api.de/api/interpreter}") String baseUrl) {
        this.baseUrl = baseUrl;
        this.restClient = builder.build();
    }

    public List<StationResponseDTO> findFuelStations(double lat, double lng, int radiusMeters) {
        String query = "[out:json][timeout:10];nwr(around:%d,%s,%s)[amenity=fuel];out center;"
                .formatted(radiusMeters, coord(lat), coord(lng));
        try {
            OverpassResponseDTO response = restClient.post()
                    .uri(baseUrl)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(query)
                    .retrieve()
                    .body(OverpassResponseDTO.class);
            if (response == null || response.getElements() == null) {
                return List.of();
            }
            return response.getElements().stream()
                    .map(this::toStationResponse)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (RestClientException e) {
            log.warn("Falha ao chamar Overpass API: {}", e.getMessage());
            throw new ExternalServiceUnavailableException("Falha ao consultar Overpass API", e);
        }
    }

    private StationResponseDTO toStationResponse(OverpassResponseDTO.Element el) {
        Double lat = el.getLat() != null ? el.getLat() : (el.getCenter() != null ? el.getCenter().getLat() : null);
        Double lon = el.getLon() != null ? el.getLon() : (el.getCenter() != null ? el.getCenter().getLon() : null);
        if (lat == null || lon == null) {
            return null;
        }
        var tags = el.getTags();
        String name = tags != null ? tags.get("name") : null;
        String street = tags != null ? tags.get("addr:street") : null;
        String houseNumber = tags != null ? tags.get("addr:housenumber") : null;
        return StationResponseDTO.builder()
                .placeId("osm:" + el.getType() + "/" + el.getId())
                .name(name != null ? name : "Posto de combustível")
                .type(StationType.FUEL)
                .rating(null)
                .latitude(lat)
                .longitude(lon)
                .street(street)
                .houseNumber(houseNumber)
                .build();
    }

    private String coord(double value) {
        return String.format(Locale.US, "%.6f", value);
    }
}
