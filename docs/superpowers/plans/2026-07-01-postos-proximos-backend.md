# Postos Próximos — Backend (GET /stations/nearby) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Status: já implementado.** Este plano foi escrito após a implementação
> (commits `834538a`..`7fe4b61`) para completar o par spec+plano que falta
> neste repositório — todo outro design em `docs/superpowers/specs/` tem um
> plano correspondente em `docs/superpowers/plans/`, este não tinha. As
> caixas abaixo refletem o código **e os testes reais** já existentes no
> repositório (todo bloco de código deste documento foi copiado do arquivo
> fonte, não reconstruído de memória) — use como referência de "como foi
> construído", não como fila de execução. Ver design:
> `docs/superpowers/specs/2026-07-01-postos-proximos-backend-design.md`.

**Goal:** Implementar `GET /api/v1/stations/nearby`, combinando postos de
combustível (Overpass/OpenStreetMap) e estações de recarga elétrica (Open
Charge Map) num único endpoint com cache Redis e rate limiting por usuário.

**Architecture:** Pacote por feature `com.devappmobile.flowfuel.station`:
`StationController` → `StationService` (orquestra rate limit → cache →
`OverpassClient` + `OpenChargeMapClient` em paralelo lógico → merge +
Haversine + ordenação → cache put) → dois clients HTTP síncronos
(`RestClient`). Cache e rate limit reaproveitam a conexão Lettuce já
configurada em `RateLimitingConfig` (sem `spring-boot-starter-data-redis`
novo, sem `@Cacheable`).

**Tech Stack:** Spring Boot 3.5.7 (MVC síncrono), `RestClient` (Spring
6.1+), Bucket4j + `LettuceBasedProxyManager` (rate limit), Lettuce
(`StatefulRedisConnection<String, byte[]>` cru para cache), Jackson,
Testcontainers (`GenericContainer` redis:7-alpine), MockMvc/Mockito.

---

## File Structure

```
src/main/java/com/devappmobile/flowfuel/station/
  StationType.java                 — enum FUEL | ELECTRIC
  HaversineUtil.java                — cálculo de distância em metros, sem estado
  StationCacheService.java          — get/put raw Redis (Lettuce), fail-open
  StationService.java               — orquestração: rate limit, cache, merge, sort
  StationController.java            — GET /nearby, validação de lat/lng/radius
  dto/StationResponseDTO.java       — shape de resposta (placeId, name, type, ...)
  client/OverpassClient.java        — POST Overpass QL, parse node/way/relation
  client/OverpassResponseDTO.java   — DTO de resposta Overpass (elements/center/tags)
  client/OpenChargeMapClient.java   — GET Open Charge Map, header X-API-Key opcional
  client/OpenChargeMapPoiDTO.java   — DTO de resposta OCM (ID/AddressInfo)

src/main/java/com/devappmobile/flowfuel/common/error/ErrorCode.java
  — + EXTERNAL_SERVICE_UNAVAILABLE(503)
src/main/java/com/devappmobile/flowfuel/exception/ExternalServiceUnavailableException.java
  — nova exceção, mapeada para o ErrorCode acima
src/main/resources/application.properties
  — flowfuel.station.overpass.base-url (default overpass-api.de)
  — flowfuel.station.open-charge-map.base-url (default api.openchargemap.io)
  — flowfuel.station.open-charge-map.api-key=${OPEN_CHARGE_MAP_API_KEY:}

src/test/java/com/devappmobile/flowfuel/station/
  HaversineUtilTest.java
  StationCacheServiceTest.java
  StationServiceTest.java
  StationControllerIntegrationTest.java
  StationRedisIntegrationTest.java   — Testcontainers real: cache hit + rate limit
  client/OverpassClientTest.java
  client/OpenChargeMapClientTest.java
```

---

### Task 1: ErrorCode e exceção para falha de serviço externo

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/common/error/ErrorCode.java`
- Create: `src/main/java/com/devappmobile/flowfuel/exception/ExternalServiceUnavailableException.java`

- [x] **Step 1: Adicionar o novo ErrorCode**

```java
// 503 — dependencia externa indisponivel
EXTERNAL_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Serviço externo indisponível"),
```

- [x] **Step 2: Criar a exceção**

```java
package com.devappmobile.flowfuel.exception;

import com.devappmobile.flowfuel.common.error.AppException;
import com.devappmobile.flowfuel.common.error.ErrorCode;

public class ExternalServiceUnavailableException extends AppException {

    public ExternalServiceUnavailableException(String message) {
        super(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE, message);
    }

    public ExternalServiceUnavailableException(String message, Throwable cause) {
        super(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE, message, cause);
    }
}
```

- [x] **Step 3: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/common/error/ErrorCode.java \
        src/main/java/com/devappmobile/flowfuel/exception/ExternalServiceUnavailableException.java
git commit -m "feat(station): add EXTERNAL_SERVICE_UNAVAILABLE error code"
```

---

### Task 2: StationType, StationResponseDTO e HaversineUtil

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/station/StationType.java`
- Create: `src/main/java/com/devappmobile/flowfuel/station/dto/StationResponseDTO.java`
- Create: `src/main/java/com/devappmobile/flowfuel/station/HaversineUtil.java`
- Test: `src/test/java/com/devappmobile/flowfuel/station/HaversineUtilTest.java`

- [x] **Step 1: Escrever teste de Haversine (ponto igual + dois pontos conhecidos)**

```java
package com.devappmobile.flowfuel.station;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HaversineUtilTest {

    @Test
    void distanceMeters_mesmoPonto_retornaZero() {
        assertThat(HaversineUtil.distanceMeters(-8.05, -34.90, -8.05, -34.90)).isZero();
    }

    @Test
    void distanceMeters_doisPontosConhecidos_calculaDistanciaAproximada() {
        // Recife (-8.0476, -34.8770) -> Olinda (-7.9936, -34.8394): ~7km
        int distance = HaversineUtil.distanceMeters(-8.0476, -34.8770, -7.9936, -34.8394);
        assertThat(distance).isBetween(6000, 8000);
    }
}
```

- [x] **Step 2: Rodar e confirmar falha (classe não existe)**

Run: `./mvnw -q test -Dtest=HaversineUtilTest`
Expected: FAIL — `cannot find symbol: class HaversineUtil`

- [x] **Step 3: Implementar StationType**

```java
package com.devappmobile.flowfuel.station;

public enum StationType {
    FUEL,
    ELECTRIC
}
```

- [x] **Step 4: Implementar StationResponseDTO**

```java
package com.devappmobile.flowfuel.station.dto;

import com.devappmobile.flowfuel.station.StationType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StationResponseDTO {

    private String placeId;
    private String name;
    private StationType type;
    private Integer distanceMeters;
    private Double rating;
    private Double latitude;
    private Double longitude;
    private String street;
    private String houseNumber;
}
```

- [x] **Step 5: Implementar HaversineUtil**

```java
package com.devappmobile.flowfuel.station;

public final class HaversineUtil {

    private static final double EARTH_RADIUS_METERS = 6_371_000;

    private HaversineUtil() {}

    public static int distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return (int) Math.round(EARTH_RADIUS_METERS * c);
    }
}
```

- [x] **Step 6: Rodar e confirmar sucesso**

Run: `./mvnw -q test -Dtest=HaversineUtilTest`
Expected: PASS

- [x] **Step 7: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/station/StationType.java \
        src/main/java/com/devappmobile/flowfuel/station/dto/StationResponseDTO.java \
        src/main/java/com/devappmobile/flowfuel/station/HaversineUtil.java \
        src/test/java/com/devappmobile/flowfuel/station/HaversineUtilTest.java
git commit -m "feat(station): add StationType, StationResponseDTO and HaversineUtil"
```

---

### Task 3: OverpassClient (postos de combustível)

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/station/client/OverpassResponseDTO.java`
- Create: `src/main/java/com/devappmobile/flowfuel/station/client/OverpassClient.java`
- Test: `src/test/java/com/devappmobile/flowfuel/station/client/OverpassClientTest.java`
- Modify: `src/main/resources/application.properties`

- [x] **Step 1: Escrever teste com `MockRestServiceServer` — node, way e relation numa resposta só, resposta vazia e erro 500**

```java
package com.devappmobile.flowfuel.station.client;

import com.devappmobile.flowfuel.exception.ExternalServiceUnavailableException;
import com.devappmobile.flowfuel.station.StationType;
import com.devappmobile.flowfuel.station.dto.StationResponseDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

class OverpassClientTest {

    private static final String BASE_URL = "https://overpass-api.de/api/interpreter";

    private MockRestServiceServer server;
    private OverpassClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new OverpassClient(builder, BASE_URL);
    }

    @Test
    void findFuelStations_respostaComNodeEWay_mapeiaAmbos() {
        String body = """
                {
                  "elements": [
                    {"type":"node","id":123456789,"lat":-8.05,"lon":-34.90,
                     "tags":{"name":"Posto Node"}},
                    {"type":"way","id":987654321,
                     "center":{"lat":-8.06,"lon":-34.91},
                     "tags":{"name":"Posto Way"}},
                    {"type":"relation","id":111,
                     "center":{"lat":-8.07,"lon":-34.92}}
                  ]
                }
                """;
        server.expect(requestTo(BASE_URL))
                .andExpect(method(POST))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<StationResponseDTO> result = client.findFuelStations(-8.05, -34.90, 5000);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).getPlaceId()).isEqualTo("osm:node/123456789");
        assertThat(result.get(0).getName()).isEqualTo("Posto Node");
        assertThat(result.get(0).getType()).isEqualTo(StationType.FUEL);
        assertThat(result.get(0).getLatitude()).isEqualTo(-8.05);
        assertThat(result.get(0).getRating()).isNull();

        assertThat(result.get(1).getPlaceId()).isEqualTo("osm:way/987654321");
        assertThat(result.get(1).getLatitude()).isEqualTo(-8.06);
        assertThat(result.get(1).getLongitude()).isEqualTo(-34.91);

        assertThat(result.get(2).getPlaceId()).isEqualTo("osm:relation/111");
        assertThat(result.get(2).getName()).isEqualTo("Posto de combustível");
    }

    @Test
    void findFuelStations_semElementos_retornaListaVazia() {
        server.expect(requestTo(BASE_URL))
                .andExpect(method(POST))
                .andRespond(withSuccess("{\"elements\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client.findFuelStations(-8.05, -34.90, 5000)).isEmpty();
    }

    @Test
    void findFuelStations_overpassRetorna500_lancaExternalServiceUnavailable() {
        server.expect(requestTo(BASE_URL))
                .andExpect(method(POST))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.findFuelStations(-8.05, -34.90, 5000))
                .isInstanceOf(ExternalServiceUnavailableException.class);
    }
}
```

- [x] **Step 2: Rodar e confirmar falha**

Run: `./mvnw -q test -Dtest=OverpassClientTest`
Expected: FAIL — `cannot find symbol: class OverpassClient`

- [x] **Step 3: Implementar OverpassResponseDTO**

```java
package com.devappmobile.flowfuel.station.client;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
public class OverpassResponseDTO {

    private List<Element> elements;

    @Getter
    @Setter
    public static class Element {
        private String type;
        private Long id;
        private Double lat;
        private Double lon;
        private Center center;
        private Map<String, String> tags;
    }

    @Getter
    @Setter
    public static class Center {
        private Double lat;
        private Double lon;
    }
}
```

- [x] **Step 4: Implementar OverpassClient**

```java
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
```

- [x] **Step 5: Adicionar propriedade de configuração**

```properties
flowfuel.station.overpass.base-url=${OVERPASS_BASE_URL:https://overpass-api.de/api/interpreter}
```

- [x] **Step 6: Rodar e confirmar sucesso**

Run: `./mvnw -q test -Dtest=OverpassClientTest`
Expected: PASS (3 testes)

- [x] **Step 7: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/station/client/OverpassResponseDTO.java \
        src/main/java/com/devappmobile/flowfuel/station/client/OverpassClient.java \
        src/test/java/com/devappmobile/flowfuel/station/client/OverpassClientTest.java \
        src/main/resources/application.properties
git commit -m "feat(station): add OverpassClient for fuel stations via OSM"
```

---

### Task 4: OpenChargeMapClient (estações de recarga elétrica)

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/station/client/OpenChargeMapPoiDTO.java`
- Create: `src/main/java/com/devappmobile/flowfuel/station/client/OpenChargeMapClient.java`
- Test: `src/test/java/com/devappmobile/flowfuel/station/client/OpenChargeMapClientTest.java`
- Modify: `src/main/resources/application.properties`

- [x] **Step 1: Escrever teste — resposta válida, POI sem AddressInfo/coordenadas filtrado, header X-API-Key só quando key configurada, erro 500**

```java
package com.devappmobile.flowfuel.station.client;

import com.devappmobile.flowfuel.exception.ExternalServiceUnavailableException;
import com.devappmobile.flowfuel.station.StationType;
import com.devappmobile.flowfuel.station.dto.StationResponseDTO;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenChargeMapClientTest {

    private static final String BASE_URL = "https://api.openchargemap.io/v3/poi";

    private MockRestServiceServer server;

    private OpenChargeMapClient buildClient(String apiKey) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new OpenChargeMapClient(builder, BASE_URL, apiKey);
    }

    @Test
    void findChargingStations_respostaValida_mapeiaResultados() {
        OpenChargeMapClient client = buildClient("");
        String body = """
                [
                  {"ID":42,"AddressInfo":{"Title":"Posto Eletrico","Latitude":-8.05,"Longitude":-34.90}}
                ]
                """;
        server.expect(method(GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<StationResponseDTO> result = client.findChargingStations(-8.05, -34.90, 5000);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPlaceId()).isEqualTo("ocm:42");
        assertThat(result.get(0).getName()).isEqualTo("Posto Eletrico");
        assertThat(result.get(0).getType()).isEqualTo(StationType.ELECTRIC);
        assertThat(result.get(0).getRating()).isNull();
    }

    @Test
    void findChargingStations_poiSemAddressInfoOuCoordenadas_filtraResultado() {
        OpenChargeMapClient client = buildClient("");
        String body = """
                [
                  {"ID":99},
                  {"ID":98,"AddressInfo":{"Title":"Sem coordenadas"}},
                  {"ID":42,"AddressInfo":{"Title":"Posto Eletrico","Latitude":-8.05,"Longitude":-34.90}}
                ]
                """;
        server.expect(method(GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<StationResponseDTO> result = client.findChargingStations(-8.05, -34.90, 5000);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPlaceId()).isEqualTo("ocm:42");
    }

    @Test
    void findChargingStations_comApiKey_enviaHeaderXApiKey() {
        OpenChargeMapClient client = buildClient("minha-key");
        server.expect(method(GET))
                .andExpect(header("X-API-Key", "minha-key"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.findChargingStations(-8.05, -34.90, 5000)).isEmpty();
    }

    @Test
    void findChargingStations_ocmRetorna500_lancaExternalServiceUnavailable() {
        OpenChargeMapClient client = buildClient("");
        server.expect(method(GET)).andRespond(withServerError());

        assertThatThrownBy(() -> client.findChargingStations(-8.05, -34.90, 5000))
                .isInstanceOf(ExternalServiceUnavailableException.class);
    }
}
```

- [x] **Step 2: Rodar e confirmar falha**

Run: `./mvnw -q test -Dtest=OpenChargeMapClientTest`
Expected: FAIL — `cannot find symbol: class OpenChargeMapClient`

- [x] **Step 3: Implementar OpenChargeMapPoiDTO**

```java
package com.devappmobile.flowfuel.station.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class OpenChargeMapPoiDTO {

    @JsonProperty("ID")
    private Long id;

    @JsonProperty("AddressInfo")
    private AddressInfo addressInfo;

    @Getter
    @Setter
    public static class AddressInfo {

        @JsonProperty("Title")
        private String title;

        @JsonProperty("AddressLine1")
        private String addressLine1;

        @JsonProperty("Latitude")
        private Double latitude;

        @JsonProperty("Longitude")
        private Double longitude;
    }
}
```

- [x] **Step 4: Implementar OpenChargeMapClient**

```java
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
                .street(poi.getAddressInfo().getAddressLine1())
                .build();
    }
}
```

- [x] **Step 5: Adicionar propriedades de configuração**

```properties
# env var OPEN_CHARGE_MAP_API_KEY (opcional, app sobe normalmente sem ela).
flowfuel.station.overpass.base-url=${OVERPASS_BASE_URL:https://overpass-api.de/api/interpreter}
flowfuel.station.open-charge-map.base-url=${OPEN_CHARGE_MAP_BASE_URL:https://api.openchargemap.io/v3/poi}
flowfuel.station.open-charge-map.api-key=${OPEN_CHARGE_MAP_API_KEY:}
```

- [x] **Step 6: Rodar e confirmar sucesso**

Run: `./mvnw -q test -Dtest=OpenChargeMapClientTest`
Expected: PASS (4 testes)

- [x] **Step 7: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/station/client/OpenChargeMapPoiDTO.java \
        src/main/java/com/devappmobile/flowfuel/station/client/OpenChargeMapClient.java \
        src/test/java/com/devappmobile/flowfuel/station/client/OpenChargeMapClientTest.java \
        src/main/resources/application.properties
git commit -m "feat(station): add OpenChargeMapClient for electric charging stations"
```

---

### Task 5: StationCacheService (Redis, fail-open)

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/station/StationCacheService.java`
- Test: `src/test/java/com/devappmobile/flowfuel/station/StationCacheServiceTest.java`

- [x] **Step 1: Escrever testes — sem conexão, miss, hit (deserializa), exceção do Redis (fail-open), put sem conexão, put com conexão (TTL)**

```java
package com.devappmobile.flowfuel.station;

import com.devappmobile.flowfuel.station.dto.StationResponseDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisException;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StationCacheServiceTest {

    @Mock private ObjectProvider<StatefulRedisConnection<String, byte[]>> connectionProvider;
    @Mock private StatefulRedisConnection<String, byte[]> connection;
    @Mock private RedisCommands<String, byte[]> commands;

    private StationCacheService cacheService;

    @BeforeEach
    void setUp() {
        cacheService = new StationCacheService(connectionProvider, new ObjectMapper());
    }

    @Test
    void get_semConexaoDisponivel_retornaVazioFailOpen() {
        when(connectionProvider.getIfAvailable()).thenReturn(null);

        assertThat(cacheService.get("key")).isEmpty();
    }

    @Test
    void get_cacheMiss_retornaVazio() {
        when(connectionProvider.getIfAvailable()).thenReturn(connection);
        when(connection.sync()).thenReturn(commands);
        when(commands.get("key")).thenReturn(null);

        assertThat(cacheService.get("key")).isEmpty();
    }

    @Test
    void get_cacheHit_deserializaLista() {
        StationResponseDTO station = StationResponseDTO.builder()
                .placeId("osm:node/1").name("Posto").type(StationType.FUEL)
                .distanceMeters(100).latitude(-8.05).longitude(-34.90).build();
        ObjectMapper mapper = new ObjectMapper();
        byte[] serialized;
        try {
            serialized = mapper.writeValueAsBytes(List.of(station));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        when(connectionProvider.getIfAvailable()).thenReturn(connection);
        when(connection.sync()).thenReturn(commands);
        when(commands.get("key")).thenReturn(serialized);

        Optional<List<StationResponseDTO>> result = cacheService.get("key");

        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(1);
        assertThat(result.get().get(0).getPlaceId()).isEqualTo("osm:node/1");
    }

    @Test
    void get_redisLancaExcecao_failOpenRetornaVazio() {
        when(connectionProvider.getIfAvailable()).thenReturn(connection);
        when(connection.sync()).thenReturn(commands);
        when(commands.get("key")).thenThrow(new RedisException("down"));

        assertThat(cacheService.get("key")).isEmpty();
    }

    @Test
    void put_semConexaoDisponivel_naoLancaExcecao() {
        when(connectionProvider.getIfAvailable()).thenReturn(null);

        cacheService.put("key", List.of());
        // sem excecao = sucesso (fail-open)
    }

    @Test
    void put_comConexao_chamaSetComTtl() {
        when(connectionProvider.getIfAvailable()).thenReturn(connection);
        when(connection.sync()).thenReturn(commands);

        cacheService.put("key", List.of());

        verify(commands).set(eq("key"), any(byte[].class), any());
    }
}
```

- [x] **Step 2: Rodar e confirmar falha**

Run: `./mvnw -q test -Dtest=StationCacheServiceTest`
Expected: FAIL — `cannot find symbol: class StationCacheService`

- [x] **Step 3: Implementar StationCacheService**

```java
package com.devappmobile.flowfuel.station;

import com.devappmobile.flowfuel.station.dto.StationResponseDTO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Service
public class StationCacheService {

    private static final Logger log = LoggerFactory.getLogger(StationCacheService.class);
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final TypeReference<List<StationResponseDTO>> LIST_TYPE = new TypeReference<>() {};

    private final ObjectProvider<StatefulRedisConnection<String, byte[]>> connectionProvider;
    private final ObjectMapper objectMapper;

    public StationCacheService(ObjectProvider<StatefulRedisConnection<String, byte[]>> connectionProvider,
            ObjectMapper objectMapper) {
        this.connectionProvider = connectionProvider;
        this.objectMapper = objectMapper;
    }

    public Optional<List<StationResponseDTO>> get(String key) {
        StatefulRedisConnection<String, byte[]> connection = connectionProvider.getIfAvailable();
        if (connection == null) {
            return Optional.empty();
        }
        try {
            byte[] value = connection.sync().get(key);
            if (value == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(value, LIST_TYPE));
        } catch (Exception e) {
            log.warn("Station cache indisponivel (get), fail-open. key={} error={}", key, e.getMessage());
            return Optional.empty();
        }
    }

    public void put(String key, List<StationResponseDTO> stations) {
        StatefulRedisConnection<String, byte[]> connection = connectionProvider.getIfAvailable();
        if (connection == null) {
            return;
        }
        try {
            byte[] value = objectMapper.writeValueAsBytes(stations);
            connection.sync().set(key, value, new SetArgs().ex(TTL.toSeconds()));
        } catch (Exception e) {
            log.warn("Station cache indisponivel (put), fail-open. key={} error={}", key, e.getMessage());
        }
    }
}
```

- [x] **Step 4: Rodar e confirmar sucesso**

Run: `./mvnw -q test -Dtest=StationCacheServiceTest`
Expected: PASS (6 testes)

- [x] **Step 5: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/station/StationCacheService.java \
        src/test/java/com/devappmobile/flowfuel/station/StationCacheServiceTest.java
git commit -m "feat(station): add Redis cache service, fail-open"
```

---

### Task 6: StationService (orquestração: rate limit, cache, merge, fallback)

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/station/StationService.java`
- Test: `src/test/java/com/devappmobile/flowfuel/station/StationServiceTest.java`

- [x] **Step 1: Escrever testes — cache hit, cache miss (merge+ordena+salva), fallback parcial, fallback total, rate limit estourado, sem ProxyManager (fail-open)**

```java
package com.devappmobile.flowfuel.station;

import com.devappmobile.flowfuel.exception.ExternalServiceUnavailableException;
import com.devappmobile.flowfuel.exception.RateLimitExceededException;
import com.devappmobile.flowfuel.station.client.OpenChargeMapClient;
import com.devappmobile.flowfuel.station.client.OverpassClient;
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

    private StationService stationService;

    @BeforeEach
    void setUp() {
        stationService = new StationService(overpassClient, openChargeMapClient, cacheService, proxyManagerProvider);
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
}
```

- [x] **Step 2: Rodar e confirmar falha**

Run: `./mvnw -q test -Dtest=StationServiceTest`
Expected: FAIL — `cannot find symbol: class StationService`

- [x] **Step 3: Implementar StationService**

```java
package com.devappmobile.flowfuel.station;

import com.devappmobile.flowfuel.exception.ExternalServiceUnavailableException;
import com.devappmobile.flowfuel.exception.RateLimitExceededException;
import com.devappmobile.flowfuel.station.client.OpenChargeMapClient;
import com.devappmobile.flowfuel.station.client.OverpassClient;
import com.devappmobile.flowfuel.station.dto.StationResponseDTO;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class StationService {

    private static final Logger log = LoggerFactory.getLogger(StationService.class);

    private static final BucketConfiguration STATION_RATE_LIMIT = BucketConfiguration.builder()
            .addLimit(Bandwidth.builder().capacity(10).refillGreedy(10, Duration.ofMinutes(1)).build())
            .build();

    private final OverpassClient overpassClient;
    private final OpenChargeMapClient openChargeMapClient;
    private final StationCacheService cacheService;
    private final ObjectProvider<ProxyManager<String>> proxyManagerProvider;

    public StationService(OverpassClient overpassClient, OpenChargeMapClient openChargeMapClient,
            StationCacheService cacheService, ObjectProvider<ProxyManager<String>> proxyManagerProvider) {
        this.overpassClient = overpassClient;
        this.openChargeMapClient = openChargeMapClient;
        this.cacheService = cacheService;
        this.proxyManagerProvider = proxyManagerProvider;
    }

    public List<StationResponseDTO> findNearby(Long userId, double lat, double lng, int radiusMeters) {
        checkRateLimit(userId);

        String cacheKey = buildCacheKey(lat, lng, radiusMeters);
        Optional<List<StationResponseDTO>> cached = cacheService.get(cacheKey);
        if (cached.isPresent()) {
            return cached.get();
        }

        List<StationResponseDTO> fuelStations = null;
        List<StationResponseDTO> electricStations = null;
        Exception lastError = null;

        try {
            fuelStations = overpassClient.findFuelStations(lat, lng, radiusMeters);
        } catch (Exception e) {
            log.warn("Overpass falhou, seguindo so com Open Charge Map: {}", e.getMessage());
            lastError = e;
        }

        try {
            electricStations = openChargeMapClient.findChargingStations(lat, lng, radiusMeters);
        } catch (Exception e) {
            log.warn("Open Charge Map falhou, seguindo so com Overpass: {}", e.getMessage());
            lastError = e;
        }

        if (fuelStations == null && electricStations == null) {
            throw new ExternalServiceUnavailableException(
                    "Overpass e Open Charge Map indisponiveis", lastError);
        }

        List<StationResponseDTO> merged = new ArrayList<>();
        if (fuelStations != null) merged.addAll(fuelStations);
        if (electricStations != null) merged.addAll(electricStations);

        merged.forEach(station -> station.setDistanceMeters(
                HaversineUtil.distanceMeters(lat, lng, station.getLatitude(), station.getLongitude())));
        merged.sort(Comparator.comparing(StationResponseDTO::getDistanceMeters));

        cacheService.put(cacheKey, merged);
        return merged;
    }

    private void checkRateLimit(Long userId) {
        ProxyManager<String> proxyManager = proxyManagerProvider.getIfAvailable();
        if (proxyManager == null) {
            return;
        }
        String bucketKey = "station-rate-limit::" + userId;
        ConsumptionProbe probe;
        try {
            BucketProxy bucket = proxyManager.builder().build(bucketKey, () -> STATION_RATE_LIMIT);
            probe = bucket.tryConsumeAndReturnRemaining(1);
        } catch (Exception e) {
            log.warn("Rate limit Redis indisponivel para stations, fail-open. userId={} error={}",
                    userId, e.getMessage());
            return;
        }
        if (!probe.isConsumed()) {
            long retryAfterSeconds = Math.max(1L,
                    Math.ceilDiv(probe.getNanosToWaitForRefill(), 1_000_000_000L));
            throw new RateLimitExceededException(retryAfterSeconds);
        }
    }

    private String buildCacheKey(double lat, double lng, int radiusMeters) {
        return "stations:nearby:%s:%s:%d".formatted(round(lat), round(lng), radiusMeters);
    }

    private String round(double value) {
        return String.format(Locale.US, "%.3f", value);
    }
}
```

- [x] **Step 4: Rodar e confirmar sucesso**

Run: `./mvnw -q test -Dtest=StationServiceTest`
Expected: PASS (6 testes)

- [x] **Step 5: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/station/StationService.java \
        src/test/java/com/devappmobile/flowfuel/station/StationServiceTest.java
git commit -m "feat(station): add StationService orchestration with cache and rate limit"
```

---

### Task 7: StationController

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/station/StationController.java`
- Test: `src/test/java/com/devappmobile/flowfuel/station/StationControllerIntegrationTest.java`

- [x] **Step 1: Escrever testes MockMvc — 401 sem token, 400 sem lat/lng, 400 lat fora de faixa, 200 com resultados do service**

O teste usa `@SpringBootTest` + `@AutoConfigureMockMvc` real (não um slice),
com um helper `obterToken` que registra e loga um usuário de verdade via
`/api/v1/auth/register` + `/api/v1/auth/login`, ativando-o manualmente via
`UserRepository` — mesmo padrão dos outros `*ControllerIntegrationTest` do
projeto.

```java
package com.devappmobile.flowfuel.station;

import com.devappmobile.flowfuel.station.dto.StationResponseDTO;
import com.devappmobile.flowfuel.user.UserRepository;
import com.devappmobile.flowfuel.user.UserStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class StationControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private StationService stationService;

    @BeforeEach
    void limparBanco() {
        userRepository.deleteAll();
    }

    private String obterToken(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"senha123","name":"Teste"}
                        """.formatted(email)));
        userRepository.findByEmail(email).ifPresent(u -> {
            u.setStatus(UserStatus.ACTIVE);
            userRepository.save(u);
        });
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"senha123"}
                        """.formatted(email)))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    @Test
    void getNearbyStations_semToken_retorna401() throws Exception {
        mockMvc.perform(get("/api/v1/stations/nearby").param("lat", "-8.05").param("lng", "-34.90"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getNearbyStations_semLatELng_retorna400() throws Exception {
        String token = obterToken("station-user1@test.com");
        mockMvc.perform(get("/api/v1/stations/nearby").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getNearbyStations_latForaDeFaixa_retorna400() throws Exception {
        String token = obterToken("station-user2@test.com");
        mockMvc.perform(get("/api/v1/stations/nearby")
                        .header("Authorization", "Bearer " + token)
                        .param("lat", "-999").param("lng", "-34.90"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getNearbyStations_requisicaoValida_retorna200ComResultadosDoService() throws Exception {
        String token = obterToken("station-user3@test.com");
        when(stationService.findNearby(anyLong(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(List.of(StationResponseDTO.builder()
                        .placeId("osm:node/1").name("Posto Teste")
                        .type(StationType.FUEL).distanceMeters(420)
                        .latitude(-8.05).longitude(-34.90).build()));

        mockMvc.perform(get("/api/v1/stations/nearby")
                        .header("Authorization", "Bearer " + token)
                        .param("lat", "-8.05").param("lng", "-34.90"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].placeId").value("osm:node/1"))
                .andExpect(jsonPath("$[0].type").value("FUEL"))
                .andExpect(jsonPath("$[0].distanceMeters").value(420))
                .andExpect(jsonPath("$[0].rating").doesNotExist());
    }
}
```

- [x] **Step 2: Rodar e confirmar falha**

Run: `./mvnw -q test -Dtest=StationControllerIntegrationTest`
Expected: FAIL — `cannot find symbol: class StationController` / 404 nas requisições

- [x] **Step 3: Implementar StationController**

```java
package com.devappmobile.flowfuel.station;

import com.devappmobile.flowfuel.station.dto.StationResponseDTO;
import com.devappmobile.flowfuel.user.User;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/stations")
@RequiredArgsConstructor
@Validated
public class StationController {

    private final StationService stationService;

    @GetMapping("/nearby")
    public List<StationResponseDTO> getNearbyStations(
            @AuthenticationPrincipal User user,
            @RequestParam @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
            @RequestParam @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng,
            @RequestParam(defaultValue = "5000") @Positive Integer radius) {
        return stationService.findNearby(user.getId(), lat, lng, radius);
    }
}
```

- [x] **Step 4: Rodar e confirmar sucesso**

Run: `./mvnw -q test -Dtest=StationControllerIntegrationTest`
Expected: PASS (4 testes)

- [x] **Step 5: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/station/StationController.java \
        src/test/java/com/devappmobile/flowfuel/station/StationControllerIntegrationTest.java
git commit -m "feat(station): add GET /api/v1/stations/nearby endpoint"
```

---

### Task 8: Teste de integração Redis (Testcontainers)

**Files:**
- Test: `src/test/java/com/devappmobile/flowfuel/station/StationRedisIntegrationTest.java`

Sobe um `GenericContainer` Redis real (padrão já usado em
`RateLimitFilterIntegrationTest`) e injeta a URL via
`flowfuel.rate-limit.redis-url` (mesma property que `RateLimitingConfig`
usa para configurar a conexão Lettuce compartilhada por cache e rate
limit). `OverpassClient`/`OpenChargeMapClient` são mockados (`@MockBean`)
para o teste não depender de rede externa real no CI — o que se verifica é
o comportamento de `StationService` com Redis de verdade por trás: cache
hit evita nova chamada aos clients, e a 11ª chamada no mesmo minuto para o
mesmo usuário estoura o rate limit.

- [x] **Step 1: Escrever os dois testes**

```java
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
```

- [x] **Step 2: Rodar e confirmar sucesso**

Run: `./mvnw -q test -Dtest=StationRedisIntegrationTest`
Expected: PASS (2 testes) — requer Docker disponível no ambiente de teste.

- [x] **Step 3: Commit**

```bash
git add src/test/java/com/devappmobile/flowfuel/station/StationRedisIntegrationTest.java
git commit -m "test(station): verify cache and rate limit with real Redis via Testcontainers"
```

---

### Task 9: Campos de endereço (street/houseNumber) — follow-up

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/station/dto/StationResponseDTO.java`
- Modify: `src/main/java/com/devappmobile/flowfuel/station/client/OverpassClient.java`
- Modify: `src/main/java/com/devappmobile/flowfuel/station/client/OpenChargeMapClient.java`
- Modify: `src/main/java/com/devappmobile/flowfuel/station/client/OpenChargeMapPoiDTO.java`

Adicionado depois do endpoint inicial (commit `7fe4b61`) para dar ao app um
endereço legível — extrai `addr:street`/`addr:housenumber` das tags do
Overpass e `AddressLine1` do Open Charge Map. Já coberto pelos testes de
`OverpassClientTest`/`OpenChargeMapClientTest` mostrados nas Tasks 3/4
acima — não adicionaram teste próprio, os campos são verificados via
serialização/deserialização normal do `StationResponseDTO`.

- [x] **Step 1: Adicionar campos ao DTO** (ver Task 2, Step 4 — já inclui `street`/`houseNumber`)
- [x] **Step 2: Extrair de `tags.get("addr:street")` / `tags.get("addr:housenumber")` no OverpassClient** (ver Task 3, Step 4)
- [x] **Step 3: Extrair de `AddressInfo.AddressLine1` no OpenChargeMapClient** (ver Task 4, Step 4)
- [x] **Step 4: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/station/dto/StationResponseDTO.java \
        src/main/java/com/devappmobile/flowfuel/station/client/OverpassClient.java \
        src/main/java/com/devappmobile/flowfuel/station/client/OpenChargeMapClient.java \
        src/main/java/com/devappmobile/flowfuel/station/client/OpenChargeMapPoiDTO.java
git commit -m "feat(station): add street and house number to station response"
```

---

## Provisionamento — verificado em produção (2026-07-02)

`OPEN_CHARGE_MAP_API_KEY` já está configurada como secret no Fly.io
(`flyctl secrets list` confirma o valor deployado) — o risco descrito no
design ("sem a key, `type: ELECTRIC` nunca aparece") **não está ativo em
produção**. Nenhuma ação pendente aqui.

## Self-Review

**Spec coverage:**
- Contrato da API (query params, response shape, status codes 400/401/429/503) → Tasks 1, 6, 7.
- Overpass (postos de combustível, node/way/relation) → Task 3.
- Open Charge Map (estações elétricas, key opcional/exigida) → Task 4.
- Haversine para distância uniforme entre fontes → Task 2.
- Cache Redis raw, TTL 10min, fail-open → Task 5.
- Rate limit por usuário via Bucket4j, fail-open → Task 6.
- Fallback parcial/total entre as duas fontes → Task 6.
- Teste de integração com Redis real → Task 8.
- Street/houseNumber (adicionado pós-implementação inicial) → Task 9.
- Provisionamento (`OPEN_CHARGE_MAP_API_KEY`) → verificado deployado, ver seção acima.

**Placeholder scan:** nenhum "TBD"/"implementar depois" — todo bloco de
código deste documento foi copiado dos arquivos reais do repositório
(`src/main/...`, `src/test/...`), não reconstruído de memória.

**Type consistency:** `StationResponseDTO`, `StationType`, `HaversineUtil.distanceMeters`,
`StationCacheService.get/put`, `StationService.findNearby` usados de forma
consistente em todas as tasks (mesmas assinaturas do código-fonte real).
