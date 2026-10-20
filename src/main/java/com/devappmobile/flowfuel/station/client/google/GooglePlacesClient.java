package com.devappmobile.flowfuel.station.client.google;

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
import java.util.Objects;

/**
 * Cliente da Google Places API (New) para busca de postos de combustível.
 * Ver docs/superpowers/specs/2026-10-20-google-places-integration-design.md.
 */
@Component
public class GooglePlacesClient {

    private static final Logger log = LoggerFactory.getLogger(GooglePlacesClient.class);
    private static final String USER_AGENT = "FlowFuel/1.0 (+https://flowfuel-api.fly.dev)";
    private static final String FIELD_MASK = "places.id,places.displayName,places.formattedAddress,places.location";

    private final RestClient restClient;
    private final String baseUrl;
    private final String apiKey;

    public GooglePlacesClient(RestClient.Builder builder,
            @Value("${flowfuel.station.google-places.base-url:https://places.googleapis.com}") String baseUrl,
            @Value("${flowfuel.station.google-places.api-key:}") String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.restClient = builder.build();
    }

    public List<StationResponseDTO> searchNearby(double lat, double lng, int radiusMeters) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ExternalServiceUnavailableException("GOOGLE_PLACES_API_KEY não configurada");
        }

        GooglePlacesRequestDTO request = GooglePlacesRequestDTO.builder()
                .location(GooglePlacesRequestDTO.Location.builder()
                        .latitude(lat)
                        .longitude(lng)
                        .build())
                .includedTypes(List.of("gas_station"))
                .maxResultCount(20)
                .rankPreference("DISTANCE")
                .build();

        try {
            GooglePlacesResponseDTO response = restClient.post()
                    .uri(baseUrl + "/v1/places:searchNearby")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Goog-Api-Key", apiKey)
                    .header("X-Goog-FieldMask", FIELD_MASK)
                    .header("User-Agent", USER_AGENT)
                    .body(request)
                    .retrieve()
                    .body(GooglePlacesResponseDTO.class);

            if (response == null || response.getPlaces() == null) {
                return List.of();
            }

            return response.getPlaces().stream()
                    .map(this::toStationResponse)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (RestClientException e) {
            log.warn("Falha ao chamar Google Places API: {}", e.getMessage());
            throw new ExternalServiceUnavailableException("Falha ao consultar Google Places API", e);
        }
    }

    private StationResponseDTO toStationResponse(GooglePlacesResponseDTO.Place place) {
        if (place.getLocation() == null
                || place.getLocation().getLatitude() == null
                || place.getLocation().getLongitude() == null) {
            return null;
        }

        String name = place.getDisplayName() != null && place.getDisplayName().getText() != null
                ? place.getDisplayName().getText()
                : "Posto de combustível";

        return StationResponseDTO.builder()
                .placeId("google:" + place.getId())
                .name(name)
                .type(StationType.FUEL)
                .rating(null)
                .latitude(place.getLocation().getLatitude())
                .longitude(place.getLocation().getLongitude())
                .street(place.getFormattedAddress())
                .houseNumber(null)
                .build();
    }
}
