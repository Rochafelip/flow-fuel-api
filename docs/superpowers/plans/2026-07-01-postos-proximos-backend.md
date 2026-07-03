# Postos Próximos — Backend (GET /stations/nearby) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `GET /api/v1/stations/nearby`, returning fuel stations (Overpass/OSM) and EV charging stations (Open Charge Map) near a point, with Redis cache, per-user rate limiting, and graceful partial/total failure handling — per `docs/superpowers/specs/2026-07-01-postos-proximos-backend-design.md`.

**Architecture:** New package-by-feature module `com.devappmobile.flowfuel.station` mirroring the existing `vehicle`/`vehicleevent` layering: `StationController` → `StationService` → `OverpassClient` + `OpenChargeMapClient` (both using Spring's `RestClient`). `StationService` also talks to a new `StationCacheService` (raw Redis GET/SET reusing the existing Lettuce connection) and to the existing Bucket4j `ProxyManager<String>` bean for per-user rate limiting.

**Tech Stack:** Spring Boot 3.5.7, Java 21, `RestClient` (`spring-boot-starter-web`, no new dependency), Lettuce/Bucket4j (already a dependency), Jackson, JUnit 5 + Mockito + AssertJ + `MockRestServiceServer` (all already in `spring-boot-starter-test`), Testcontainers (already a dependency).

---

## Important design note (read before starting)

The existing `RateLimitingConfig` (`src/main/java/com/devappmobile/flowfuel/config/RateLimitingConfig.java`) is annotated `@ConditionalOnProperty(name = "flowfuel.rate-limit.enabled", havingValue = "true", matchIfMissing = true)`. Test `application.properties` (`src/test/resources/application.properties:13`) sets `flowfuel.rate-limit.enabled=false`, so in most existing tests **the `ProxyManager<String>` and `StatefulRedisConnection<String, byte[]>` beans do not exist in the Spring context at all** (not just "Redis down" — the bean is absent).

If `StationService`/`StationCacheService` took these as plain constructor-injected `final` fields, every unrelated `@SpringBootTest` in the suite that doesn't override `flowfuel.rate-limit.enabled=true` would fail to start the context (`NoSuchBeanDefinitionException`), because Spring can't autowire a bean that doesn't exist.

**Fix:** inject both dependencies as `ObjectProvider<T>` and call `.getIfAvailable()`. If `null`, treat exactly like the existing "Redis unavailable" fail-open case (skip cache / skip rate limit, log a warning). This is consistent with the spec's own fail-open philosophy for both cache and rate limiting, and requires zero changes to `RateLimitingConfig` or test properties.

---

## File Structure

Create:
- `src/main/java/com/devappmobile/flowfuel/common/error/ErrorCode.java` — modify, add `EXTERNAL_SERVICE_UNAVAILABLE`
- `src/main/java/com/devappmobile/flowfuel/exception/ExternalServiceUnavailableException.java`
- `src/main/java/com/devappmobile/flowfuel/exception/RateLimitExceededException.java`
- `src/main/java/com/devappmobile/flowfuel/config/GlobalExceptionHandler.java` — modify, add handler for `RateLimitExceededException` (sets `Retry-After`)
- `src/main/java/com/devappmobile/flowfuel/station/StationType.java`
- `src/main/java/com/devappmobile/flowfuel/station/dto/StationResponseDTO.java`
- `src/main/java/com/devappmobile/flowfuel/station/HaversineUtil.java`
- `src/main/java/com/devappmobile/flowfuel/station/client/OverpassResponseDTO.java`
- `src/main/java/com/devappmobile/flowfuel/station/client/OverpassClient.java`
- `src/main/java/com/devappmobile/flowfuel/station/client/OpenChargeMapPoiDTO.java`
- `src/main/java/com/devappmobile/flowfuel/station/client/OpenChargeMapClient.java`
- `src/main/java/com/devappmobile/flowfuel/station/StationCacheService.java`
- `src/main/java/com/devappmobile/flowfuel/station/StationService.java`
- `src/main/java/com/devappmobile/flowfuel/station/StationController.java`
- `src/main/resources/application.properties` — modify, add station config block
- Tests mirroring each file under `src/test/java/com/devappmobile/flowfuel/station/...` and `src/test/java/com/devappmobile/flowfuel/exception/...`

---

### Task 1: ErrorCode + new exceptions + Retry-After handling

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/common/error/ErrorCode.java`
- Create: `src/main/java/com/devappmobile/flowfuel/exception/ExternalServiceUnavailableException.java`
- Create: `src/main/java/com/devappmobile/flowfuel/exception/RateLimitExceededException.java`
- Modify: `src/main/java/com/devappmobile/flowfuel/config/GlobalExceptionHandler.java`
- Test: `src/test/java/com/devappmobile/flowfuel/config/GlobalExceptionHandlerRateLimitTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.devappmobile.flowfuel.config;

import com.devappmobile.flowfuel.exception.RateLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerRateLimitTest {

    @Test
    void handleRateLimitExceeded_retorna429ComRetryAfterHeader() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        HttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/stations/nearby");

        ResponseEntity<ProblemDetail> response =
                handler.handleRateLimitExceeded(new RateLimitExceededException(30), req);

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("30");
        assertThat(response.getBody().getProperties().get("code")).isEqualTo("RATE_LIMIT_EXCEEDED");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=GlobalExceptionHandlerRateLimitTest`
Expected: FAIL (compile error — `RateLimitExceededException` and `handleRateLimitExceeded` don't exist yet)

- [ ] **Step 3: Add the `EXTERNAL_SERVICE_UNAVAILABLE` error code**

In `src/main/java/com/devappmobile/flowfuel/common/error/ErrorCode.java`, add a new section right after `RATE_LIMIT_EXCEEDED` and before `INTERNAL_ERROR`:

```java
    // 503 — dependencia externa indisponivel
    EXTERNAL_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Serviço externo indisponível"),

    // 500 — generico
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno");
```

(replace the existing final `INTERNAL_ERROR(...)` line, keeping it as the last entry with the terminating semicolon).

- [ ] **Step 4: Create the two new exception classes**

`src/main/java/com/devappmobile/flowfuel/exception/ExternalServiceUnavailableException.java`:

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

`src/main/java/com/devappmobile/flowfuel/exception/RateLimitExceededException.java`:

```java
package com.devappmobile.flowfuel.exception;

import com.devappmobile.flowfuel.common.error.AppException;
import com.devappmobile.flowfuel.common.error.ErrorCode;

public class RateLimitExceededException extends AppException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(long retryAfterSeconds) {
        super(ErrorCode.RATE_LIMIT_EXCEEDED,
                "Limite de requisições excedido. Tente novamente em " + retryAfterSeconds + " segundos.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
```

- [ ] **Step 5: Add the Retry-After handler to `GlobalExceptionHandler`**

In `src/main/java/com/devappmobile/flowfuel/config/GlobalExceptionHandler.java`, add the import:

```java
import com.devappmobile.flowfuel.exception.RateLimitExceededException;
import org.springframework.http.HttpHeaders;
```

(`HttpHeaders` is already imported — check before adding a duplicate.)

Add a new handler **before** `handleAppException` (Spring picks the most specific match regardless of order, but keep it visually next to related code):

```java
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimitExceeded(RateLimitExceededException ex,
            HttpServletRequest req) {
        logClientError(ex.getErrorCode(), req, ex.getMessage());
        ProblemDetail pd = problemDetail(ex.getErrorCode(), ex.getMessage(), req.getRequestURI());
        return ResponseEntity.status(ex.getErrorCode().status())
                .header(HttpHeaders.RETRY_AFTER, Long.toString(ex.getRetryAfterSeconds()))
                .body(pd);
    }
```

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn -q test -Dtest=GlobalExceptionHandlerRateLimitTest`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/common/error/ErrorCode.java \
        src/main/java/com/devappmobile/flowfuel/exception/ExternalServiceUnavailableException.java \
        src/main/java/com/devappmobile/flowfuel/exception/RateLimitExceededException.java \
        src/main/java/com/devappmobile/flowfuel/config/GlobalExceptionHandler.java \
        src/test/java/com/devappmobile/flowfuel/config/GlobalExceptionHandlerRateLimitTest.java
git commit -m "feat(station): add EXTERNAL_SERVICE_UNAVAILABLE error code and rate-limit Retry-After handling"
```

---

### Task 2: StationType, StationResponseDTO, HaversineUtil

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/station/StationType.java`
- Create: `src/main/java/com/devappmobile/flowfuel/station/dto/StationResponseDTO.java`
- Create: `src/main/java/com/devappmobile/flowfuel/station/HaversineUtil.java`
- Test: `src/test/java/com/devappmobile/flowfuel/station/HaversineUtilTest.java`

- [ ] **Step 1: Write the failing test**

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

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=HaversineUtilTest`
Expected: FAIL (compile error — `HaversineUtil` doesn't exist yet)

- [ ] **Step 3: Write `StationType`**

```java
package com.devappmobile.flowfuel.station;

public enum StationType {
    FUEL,
    ELECTRIC
}
```

- [ ] **Step 4: Write `StationResponseDTO`**

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
}
```

- [ ] **Step 5: Write `HaversineUtil`**

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

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn -q test -Dtest=HaversineUtilTest`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/station/StationType.java \
        src/main/java/com/devappmobile/flowfuel/station/dto/StationResponseDTO.java \
        src/main/java/com/devappmobile/flowfuel/station/HaversineUtil.java \
        src/test/java/com/devappmobile/flowfuel/station/HaversineUtilTest.java
git commit -m "feat(station): add StationType, StationResponseDTO and Haversine distance utility"
```

---

### Task 3: OverpassClient (fuel stations)

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/station/client/OverpassResponseDTO.java`
- Create: `src/main/java/com/devappmobile/flowfuel/station/client/OverpassClient.java`
- Test: `src/test/java/com/devappmobile/flowfuel/station/client/OverpassClientTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.devappmobile.flowfuel.station.client;

import com.devappmobile.flowfuel.exception.ExternalServiceUnavailableException;
import com.devappmobile.flowfuel.station.StationType;
import com.devappmobile.flowfuel.station.dto.StationResponseDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
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

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=OverpassClientTest`
Expected: FAIL (compile error — `OverpassClient` doesn't exist yet)

- [ ] **Step 3: Write `OverpassResponseDTO`**

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

- [ ] **Step 4: Write `OverpassClient`**

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
        String name = el.getTags() != null ? el.getTags().get("name") : null;
        return StationResponseDTO.builder()
                .placeId("osm:" + el.getType() + "/" + el.getId())
                .name(name != null ? name : "Posto de combustível")
                .type(StationType.FUEL)
                .rating(null)
                .latitude(lat)
                .longitude(lon)
                .build();
    }

    private String coord(double value) {
        return String.format(Locale.US, "%.6f", value);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q test -Dtest=OverpassClientTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/station/client/OverpassResponseDTO.java \
        src/main/java/com/devappmobile/flowfuel/station/client/OverpassClient.java \
        src/test/java/com/devappmobile/flowfuel/station/client/OverpassClientTest.java
git commit -m "feat(station): add OverpassClient for fuel station lookup"
```

---

### Task 4: OpenChargeMapClient (EV charging stations)

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/station/client/OpenChargeMapPoiDTO.java`
- Create: `src/main/java/com/devappmobile/flowfuel/station/client/OpenChargeMapClient.java`
- Test: `src/test/java/com/devappmobile/flowfuel/station/client/OpenChargeMapClientTest.java`

- [ ] **Step 1: Write the failing test**

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
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestToUriTemplate;
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

Note: `requestToUriTemplate` import above is unused if you only assert on method/header — remove it if your IDE flags unused imports; kept here only to show it's available if you want to add stricter query-param assertions.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=OpenChargeMapClientTest`
Expected: FAIL (compile error — `OpenChargeMapClient` doesn't exist yet)

- [ ] **Step 3: Write `OpenChargeMapPoiDTO`**

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

        @JsonProperty("Latitude")
        private Double latitude;

        @JsonProperty("Longitude")
        private Double longitude;
    }
}
```

- [ ] **Step 4: Write `OpenChargeMapClient`**

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
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q test -Dtest=OpenChargeMapClientTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/station/client/OpenChargeMapPoiDTO.java \
        src/main/java/com/devappmobile/flowfuel/station/client/OpenChargeMapClient.java \
        src/test/java/com/devappmobile/flowfuel/station/client/OpenChargeMapClientTest.java
git commit -m "feat(station): add OpenChargeMapClient for EV charging station lookup"
```

---

### Task 5: StationCacheService (Redis, fail-open)

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/station/StationCacheService.java`
- Test: `src/test/java/com/devappmobile/flowfuel/station/StationCacheServiceTest.java`

- [ ] **Step 1: Write the failing test**

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

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=StationCacheServiceTest`
Expected: FAIL (compile error — `StationCacheService` doesn't exist yet)

- [ ] **Step 3: Write `StationCacheService`**

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

/**
 * Cache Redis raw (nao @Cacheable) para resultados de busca de postos/estacoes.
 * Reaproveita a conexao Lettuce de RateLimitingConfig — ver nota de design no
 * plano em docs/superpowers/plans/2026-07-01-postos-proximos-backend.md sobre
 * por que a conexao chega como ObjectProvider (pode nao existir quando
 * flowfuel.rate-limit.enabled=false). Fail-open: qualquer falha vira cache miss.
 */
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
            connection.sync().set(key, value, SetArgs.builder().ex(TTL.toSeconds()).build());
        } catch (Exception e) {
            log.warn("Station cache indisponivel (put), fail-open. key={} error={}", key, e.getMessage());
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=StationCacheServiceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/station/StationCacheService.java \
        src/test/java/com/devappmobile/flowfuel/station/StationCacheServiceTest.java
git commit -m "feat(station): add Redis-backed StationCacheService with fail-open behavior"
```

---

### Task 6: StationService (merge, rate limit, cache, fallback)

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/station/StationService.java`
- Test: `src/test/java/com/devappmobile/flowfuel/station/StationServiceTest.java`

- [ ] **Step 1: Write the failing test**

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
        when(bucketBuilder.build(any(), any())).thenReturn(bucketProxy);
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
        when(bucketBuilder.build(any(), any())).thenReturn(bucketProxy);
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

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=StationServiceTest`
Expected: FAIL (compile error — `StationService` doesn't exist yet)

- [ ] **Step 3: Write `StationService`**

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

/**
 * Orquestra a busca de postos proximos: cache -> Overpass + Open Charge Map ->
 * merge + distancia (Haversine) + ordenacao -> cache put. Rate limit por
 * userId antes de qualquer chamada. Fail-open tanto para cache quanto para
 * rate limit quando o Redis/ProxyManager nao estao disponiveis — ver nota de
 * design no plano em docs/superpowers/plans/2026-07-01-postos-proximos-backend.md.
 */
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
        BucketProxy bucket = proxyManager.builder().build(bucketKey, () -> STATION_RATE_LIMIT);
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
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

Note: `checkRateLimit` intentionally does **not** catch/fail-open on exceptions thrown by `proxyManager.builder()...tryConsumeAndReturnRemaining(...)` (e.g. Redis down at runtime after the bean already existed) — add that fail-open behavior now:

- [ ] **Step 4: Add runtime fail-open around the rate-limit check**

Replace the body of `checkRateLimit` with:

```java
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
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q test -Dtest=StationServiceTest`
Expected: PASS (all 7 tests)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/station/StationService.java \
        src/test/java/com/devappmobile/flowfuel/station/StationServiceTest.java
git commit -m "feat(station): add StationService with cache, rate limit and partial-failure merge logic"
```

---

### Task 7: StationController + validation

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/station/StationController.java`
- Test: `src/test/java/com/devappmobile/flowfuel/station/StationControllerIntegrationTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.devappmobile.flowfuel.station;

import com.devappmobile.flowfuel.station.dto.StationResponseDTO;
import com.devappmobile.flowfuel.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

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
        var user = userRepository.findByEmail(email).orElseThrow();
        user.setActive(true);
        userRepository.save(user);
        var loginResult = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"senha123"}
                        """.formatted(email)))
                .andReturn();
        var json = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(loginResult.getResponse().getContentAsString());
        return json.get("token").asText();
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

Before writing this test, check the exact registration/login/activation request shapes and field names (email/password/name; whether `active` is the correct setter, whether login returns `token`) by reading `src/test/java/com/devappmobile/flowfuel/vehicle/VehicleControllerIntegrationTest.java`'s `obterToken` helper in full, and mirror it exactly — do not guess field names.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=StationControllerIntegrationTest`
Expected: FAIL (compile error — `StationController` doesn't exist yet)

- [ ] **Step 3: Write `StationController`**

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

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=StationControllerIntegrationTest`
Expected: PASS. If the 400 tests instead return 500, it means `ConstraintViolationException` isn't being thrown for `@RequestParam` — verify `@Validated` is present at class level (required for Spring to validate `@RequestParam`/`@PathVariable` method parameters) and that the existing `GlobalExceptionHandler.handleConstraintViolation` (`config/GlobalExceptionHandler.java:54`) already covers it (it does — no changes needed there).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/station/StationController.java \
        src/test/java/com/devappmobile/flowfuel/station/StationControllerIntegrationTest.java
git commit -m "feat(station): add GET /api/v1/stations/nearby controller with param validation"
```

---

### Task 8: Application config (Open Charge Map key, base URLs)

**Files:**
- Modify: `src/main/resources/application.properties`

- [ ] **Step 1: Add the station config block**

Add near the other feature config blocks (after the rate-limit block, following the existing commented-block convention seen at lines 62-65 and 83-84):

```properties
# Postos proximos (GET /api/v1/stations/nearby): Overpass API (OSM, sem key)
# e Open Charge Map (sem key obrigatoria; com key aumenta o rate limit).
# Gere uma key gratuita em openchargemap.org se necessario e configure via
# env var OPEN_CHARGE_MAP_API_KEY (opcional, app sobe normalmente sem ela).
flowfuel.station.overpass.base-url=${OVERPASS_BASE_URL:https://overpass-api.de/api/interpreter}
flowfuel.station.open-charge-map.base-url=${OPEN_CHARGE_MAP_BASE_URL:https://api.openchargemap.io/v3/poi}
flowfuel.station.open-charge-map.api-key=${OPEN_CHARGE_MAP_API_KEY:}
```

- [ ] **Step 2: Verify the app still boots**

Run: `mvn -q spring-boot:run -Dspring-boot.run.profiles=default &` then check `curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/actuator/health` returns `200`, then stop the process. If there's no convenient way to background-check in this environment, instead run: `mvn -q test-compile` to confirm no property-binding errors, and rely on Task 7's `@SpringBootTest` passing as the real proof the context loads with these properties.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/application.properties
git commit -m "feat(station): add Overpass/Open Charge Map config properties"
```

---

### Task 9: Redis integration test (cache + rate limit, real Redis via Testcontainers)

**Files:**
- Create: `src/test/java/com/devappmobile/flowfuel/station/StationRedisIntegrationTest.java`

This mirrors `RateLimitFilterIntegrationTest` (`src/test/java/com/devappmobile/flowfuel/config/RateLimitFilterIntegrationTest.java`): real Redis via Testcontainers, `flowfuel.rate-limit.enabled=true` so the shared `ProxyManager<String>`/`StatefulRedisConnection` beans exist, and `OverpassClient`/`OpenChargeMapClient` mocked via `@MockBean` (no real network calls in CI) so the test isolates cache + rate-limit behavior end to end.

- [ ] **Step 1: Write the test**

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

import static org.assertj.core.api.Assertions.assertThat;
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
            // varia lat/lng para nao bater no cache e garantir que o rate limit
            // e avaliado antes do cache em cada chamada
            stationService.findNearby(userId, -8.0 - (i * 0.01), -34.0, 5000);
        }

        assertThatThrownBy(() -> stationService.findNearby(userId, -8.5, -34.0, 5000))
                .isInstanceOf(com.devappmobile.flowfuel.exception.RateLimitExceededException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it passes**

Run: `mvn -q test -Dtest=StationRedisIntegrationTest`
Expected: PASS (requires Docker available for Testcontainers, same requirement as the existing `RateLimitFilterIntegrationTest`)

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/devappmobile/flowfuel/station/StationRedisIntegrationTest.java
git commit -m "test(station): add Redis-backed integration test for cache and rate limit"
```

---

### Task 10: Full suite run and final check

- [ ] **Step 1: Run the full test suite**

Run: `mvn -q test`
Expected: BUILD SUCCESS, all tests pass including the pre-existing suite (confirms the `ObjectProvider` fix in Task 5/6 didn't break any context loading elsewhere).

- [ ] **Step 2: Spot-check OpenAPI/Swagger picks up the new endpoint (springdoc is already on the classpath)**

Run: `mvn -q spring-boot:run &` then `curl -s http://localhost:8080/v3/api-docs | grep -o '"/api/v1/stations/nearby"'`, then stop the process (`kill %1` or equivalent). Expected: the path string is present in the generated OpenAPI doc.

- [ ] **Step 3: Final commit if anything was left uncommitted**

```bash
git status
```

If clean, nothing to do — every task above already committed its own changes.
