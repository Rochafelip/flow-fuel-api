package com.devappmobile.flowfuel.station.client.google;

import com.devappmobile.flowfuel.exception.ExternalServiceUnavailableException;
import com.devappmobile.flowfuel.station.StationType;
import com.devappmobile.flowfuel.station.dto.StationResponseDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

    private final String baseUrl;
    private final String apiKey;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public GooglePlacesClient(
            @Value("${flowfuel.station.google-places.base-url:https://places.googleapis.com}") String baseUrl,
            @Value("${flowfuel.station.google-places.api-key:}") String apiKey,
            ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build();
    }

    public List<StationResponseDTO> searchNearby(double lat, double lng, int radiusMeters) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ExternalServiceUnavailableException("GOOGLE_PLACES_API_KEY não configurada");
        }

        String jsonBody = String.format(
                "{\"locationRestriction\":{\"circle\":{\"center\":{\"latitude\":%s,\"longitude\":%s},\"radius\":%s}},\"includedTypes\":[\"gas_station\"],\"maxResultCount\":60,\"rankPreference\":\"DISTANCE\"}",
                lat, lng, radiusMeters
        );

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/places:searchNearby"))
                    .header("Content-Type", "application/json")
                    .header("X-Goog-Api-Key", apiKey)
                    .header("X-Goog-FieldMask", FIELD_MASK)
                    .header("User-Agent", USER_AGENT)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String responseBody = response.body();

            log.info("Google Places API response status: {}", response.statusCode());
            log.info("Google Places API response: {}", responseBody);

            GooglePlacesResponseDTO googleResponse = objectMapper.readValue(responseBody, GooglePlacesResponseDTO.class);

            if (googleResponse == null || googleResponse.getPlaces() == null) {
                return List.of();
            }

            return googleResponse.getPlaces().stream()
                    .map(this::toStationResponse)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (java.net.http.HttpTimeoutException e) {
            log.error("Timeout ao chamar Google Places API", e);
            throw new ExternalServiceUnavailableException("Timeout ao consultar Google Places API", e);
        } catch (java.io.IOException e) {
            log.error("Erro de I/O ao chamar Google Places API", e);
            throw new ExternalServiceUnavailableException("Erro de conexão ao consultar Google Places API", e);
        } catch (Exception e) {
            log.error("Falha ao chamar Google Places API", e);
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
