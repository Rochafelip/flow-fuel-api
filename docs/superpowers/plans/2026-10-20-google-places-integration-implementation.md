# Plano de Implementação: Integração Google Places API (New)

**Data**: 2026-10-20
**Baseado em**: docs/superpowers/specs/2026-10-20-google-places-integration-design.md

## Tarefas

### 1. Criar DTOs Google Places

**Arquivos a criar**:
- `src/main/java/com/devappmobile/flowfuel/station/client/google/GooglePlacesRequestDTO.java`
- `src/main/java/com/devappmobile/flowfuel/station/client/google/GooglePlacesResponseDTO.java`

**GooglePlacesRequestDTO**:
```java
package com.devappmobile.flowfuel.station.client.google;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GooglePlacesRequestDTO {
    private Location location;
    private List<String> includedTypes;
    private Integer maxResultCount;
    private String rankPreference;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Location {
        private Double latitude;
        private Double longitude;
    }
}
```

**GooglePlacesResponseDTO**:
```java
package com.devappmobile.flowfuel.station.client.google;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GooglePlacesResponseDTO {
    private List<Place> places;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Place {
        private String id;
        private DisplayName displayName;
        private String formattedAddress;
        private Location location;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DisplayName {
        @JsonProperty("text")
        private String text;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Location {
        private Double latitude;
        private Double longitude;
    }
}
```

### 2. Criar GooglePlacesClient

**Arquivo**: `src/main/java/com/devappmobile/flowfuel/station/client/google/GooglePlacesClient.java`

```java
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
```

### 3. Criar GooglePlacesKeyValidator

**Arquivo**: `src/main/java/com/devappmobile/flowfuel/station/client/google/GooglePlacesKeyValidator.java`

```java
package com.devappmobile.flowfuel.station.client.google;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
public class GooglePlacesKeyValidator implements ApplicationListener<ApplicationReadyEvent> {

    @Value("${flowfuel.station.google-places.api-key:}")
    private String apiKey;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "GOOGLE_PLACES_API_KEY é obrigatória em produção. Configure via env var.");
        }
    }
}
```

### 4. Atualizar StationService

**Arquivo**: `src/main/java/com/devappmobile/flowfuel/station/StationService.java`

Adicionar:
- Injeção de `GooglePlacesClient`
- Método `findNearbyGoogle()`

```java
// Adicionar no topo, junto com as outras dependências
private final GooglePlacesClient googlePlacesClient;

// Atualizar o construtor
public StationService(OverpassClient overpassClient, OpenChargeMapClient openChargeMapClient,
        StationCacheService cacheService, ObjectProvider<ProxyManager<String>> proxyManagerProvider,
        NominatimClient nominatimClient, GeocodeCacheService geocodeCacheService,
        GooglePlacesClient googlePlacesClient) {
    this.overpassClient = overpassClient;
    this.openChargeMapClient = openChargeMapClient;
    this.cacheService = cacheService;
    this.proxyManagerProvider = proxyManagerProvider;
    this.nominatimClient = nominatimClient;
    this.geocodeCacheService = geocodeCacheService;
    this.googlePlacesClient = googlePlacesClient;
}

// Adicionar novo método
public List<StationResponseDTO> findNearbyGoogle(Long userId, double lat, double lng, int radiusMeters) {
    checkRateLimit(userId);

    String cacheKey = "google:" + buildCacheKey(lat, lng, radiusMeters);
    Optional<List<StationResponseDTO>> cached = cacheService.get(cacheKey);
    if (cached.isPresent()) {
        return cached.get();
    }

    List<StationResponseDTO> stations = googlePlacesClient.searchNearby(lat, lng, radiusMeters);

    stations.forEach(station -> station.setDistanceMeters(
            HaversineUtil.distanceMeters(lat, lng, station.getLatitude(), station.getLongitude())));
    stations.sort(Comparator.comparing(StationResponseDTO::getDistanceMeters));

    cacheService.put(cacheKey, stations);
    return stations;
}
```

### 5. Atualizar StationController

**Arquivo**: `src/main/java/com/devappmobile/flowfuel/station/StationController.java`

Adicionar:
- Import `@Max`
- Novo método `getNearbyStationsGoogle()`

```java
// Adicionar import
import jakarta.validation.constraints.Max;

// Adicionar novo método
@GetMapping("/nearby-google")
public List<StationResponseDTO> getNearbyStationsGoogle(
        @AuthenticationPrincipal User user,
        @RequestParam @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
        @RequestParam @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng,
        @RequestParam(defaultValue = "5000") @Positive @Max(50000) Integer radius) {
    return stationService.findNearbyGoogle(user.getId(), lat, lng, radius);
}
```

### 6. Atualizar application.properties

**Arquivo**: `src/main/resources/application.properties`

Adicionar após as configurações de Open Charge Map:

```properties
# Google Places API (New) para busca de postos de combustível
flowfuel.station.google-places.base-url=${GOOGLE_PLACES_BASE_URL:https://places.googleapis.com}
flowfuel.station.google-places.api-key=${GOOGLE_PLACES_API_KEY:}
```

### 7. Atualizar .env.example

**Arquivo**: `.env.example`

Adicionar após `OPEN_CHARGE_MAP_API_KEY`:

```properties
# Google Places API Key (New) - gere em console.cloud.google.com
# Necessária para o endpoint /api/v1/stations/nearby-google
GOOGLE_PLACES_API_KEY=<sua-key-do-google-places>
```

### 8. Criar testes unitários para GooglePlacesClient

**Arquivo**: `src/test/java/com/devappmobile/flowfuel/station/client/google/GooglePlacesClientTest.java`

```java
package com.devappmobile.flowfuel.station.client.google;

import com.devappmobile.flowfuel.station.StationType;
import com.devappmobile.flowfuel.station.dto.StationResponseDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GooglePlacesClientTest {

    private RestClient restClient;
    private GooglePlacesClient client;

    @BeforeEach
    void setUp() {
        restClient = mock(RestClient.class);
        client = new GooglePlacesClient(RestClient.builder(), "https://places.googleapis.com", "test-key");
    }

    @Test
    void searchNearby_withValidResponse_returnsStations() {
        // TODO: Implementar teste com mock do RestClient
    }

    @Test
    void searchNearby_withEmptyResponse_returnsEmptyList() {
        // TODO: Implementar teste
    }

    @Test
    void searchNearby_withInvalidKey_throwsException() {
        GooglePlacesClient clientWithoutKey = new GooglePlacesClient(
                RestClient.builder(), "https://places.googleapis.com", "");

        // TODO: Implementar teste
    }
}
```

### 9. Criar testes de integração para novo endpoint

**Arquivo**: `src/test/java/com/devappmobile/flowfuel/station/StationControllerGoogleIntegrationTest.java`

```java
package com.devappmobile.flowfuel.station;

import com.devappmobile.flowfuel.station.client.google.GooglePlacesClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class StationControllerGoogleIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private GooglePlacesClient googlePlacesClient;

    @BeforeEach
    void setUp() {
        when(googlePlacesClient.searchNearby(anyDouble(), anyDouble(), anyInt()))
                .thenReturn(List.of());
    }

    @Test
    @WithMockUser
    void getNearbyStationsGoogle_withValidParams_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/stations/nearby-google")
                .param("lat", "-23.5505")
                .param("lng", "-46.6333")
                .param("radius", "1000"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser
    void getNearbyStationsGoogle_withInvalidLat_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/stations/nearby-google")
                .param("lat", "100")
                .param("lng", "-46.6333"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void getNearbyStationsGoogle_withRadiusTooLarge_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/stations/nearby-google")
                .param("lat", "-23.5505")
                .param("lng", "-46.6333")
                .param("radius", "60000"))
                .andExpect(status().isBadRequest());
    }
}
```

### 10. Teste manual

Após implementação:

```bash
# Compilar
mvn clean compile

# Subir aplicação
export JWT_SECRET="92QU5QhbYX4UNK6fcelRYyW5eIs1/iQq/80Zyooyk4="
export GOOGLE_PLACES_API_KEY="<sua-key-real>"
mvn spring-boot:run

# Em outro terminal, testar
TOKEN=$(curl -s -X POST http://localhost:8090/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"dev@flowfuel.local","password":"Dev@12345"}' \
  | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)

curl -s "http://localhost:8090/api/v1/stations/nearby-google?lat=-23.5505&lng=-46.6333&radius=1000" \
  -H "Authorization: Bearer $TOKEN"
```

### 11. Configurar no Fly.io

```bash
flyctl secrets set GOOGLE_PLACES_API_KEY="<sua-key>" -a flowfuel-api
```

### 12. Atualizar documentação

Atualizar:
- `docs/inventory.md` - Adicionar endpoint `/api/v1/stations/nearby-google`
- `docs/spec/openapi.yaml` - Adicionar especificação OpenAPI
- `docs/endpoint-flows/postos.md` - Atualizar fluxo técnico
- `README.md` - Documentar novo endpoint
- `docs/deploy.md` - Adicionar instrução para Fly.io

## Ordem de Execução

1. Criar DTOs (tarefa 1)
2. Criar GooglePlacesClient (tarefa 2)
3. Criar GooglePlacesKeyValidator (tarefa 3)
4. Atualizar StationService (tarefa 4)
5. Atualizar StationController (tarefa 5)
6. Atualizar application.properties (tarefa 6)
7. Atualizar .env.example (tarefa 7)
8. Compilar e verificar erros
9. Criar testes unitários (tarefa 8)
10. Criar testes de integração (tarefa 9)
11. Rodar testes existentes para garantir não quebrar nada
12. Teste manual (tarefa 10)
13. Commit e push
14. Configurar no Fly.io (tarefa 11)
15. Atualizar documentação (tarefa 12)
16. Commit final de documentação
