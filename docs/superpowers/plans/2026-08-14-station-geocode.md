# Endpoint de geocodificação para busca de postos por localidade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Novo endpoint `GET /stations/geocode?query={texto}` que resolve bairro/cidade em até 5 candidatos com nome completo e coordenadas, via proxy do Nominatim (OpenStreetMap).

**Architecture:** Mesmo padrão em camadas já usado para Overpass/Open Charge Map: `NominatimClient` (HTTP) → `StationService.geocode` (rate limit + cache + orquestração) → `StationController` (validação + auth). Cache Redis próprio com TTL longo (endereço não muda); rate limit global de 1/seg (política do Nominatim) além do limite por usuário já existente.

**Tech Stack:** Java, Spring Boot, `RestClient`, Redis (Lettuce), Bucket4j, JUnit 5 + Mockito + AssertJ + `MockRestServiceServer`.

## Global Constraints

- Design aprovado em `docs/superpowers/specs/2026-08-14-station-geocode-design.md`.
- `countrycodes=br` fixo — sem suporte a outros países.
- Nominatim exige `User-Agent` identificável em toda chamada: `"FlowFuel/1.0 (+https://flowfuel-api.fly.dev)"` (mesma string já usada em `OverpassClient`/`OpenChargeMapClient`).
- Rate limit global de 1 req/seg é **obrigatório** — não pular essa parte mesmo que pareça redundante com o limite por usuário; violar a política do Nominatim arrisca o IP do backend inteiro ser bloqueado.
- Nenhuma mudança em `/stations/nearby`, `StationCacheService`, `OverpassClient` ou `OpenChargeMapClient` — tudo aditivo.
- Sub-projeto B de 3 (A — filtros centralizados no Android — já implementado; C — UI de pesquisa no Android — spec/plano separado, depende do contrato aqui).

---

### Task 1: `NominatimClient` — cliente HTTP do Nominatim

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/station/dto/GeocodeResultDTO.java`
- Create: `src/main/java/com/devappmobile/flowfuel/station/client/NominatimResultDTO.java`
- Create: `src/main/java/com/devappmobile/flowfuel/station/client/NominatimClient.java`
- Test: `src/test/java/com/devappmobile/flowfuel/station/client/NominatimClientTest.java`

**Interfaces:**
- Produces: `GeocodeResultDTO { String displayName; Double latitude; Double longitude; }` (Lombok `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`), consumido pela Task 2 (cache) e Task 3 (service).
- Produces: `NominatimClient.search(String query): List<GeocodeResultDTO>`, consumido pela Task 3.

- [ ] **Step 1: Escrever o teste que falha**

Criar `src/test/java/com/devappmobile/flowfuel/station/client/NominatimClientTest.java`:

```java
package com.devappmobile.flowfuel.station.client;

import com.devappmobile.flowfuel.exception.ExternalServiceUnavailableException;
import com.devappmobile.flowfuel.station.dto.GeocodeResultDTO;
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

class NominatimClientTest {

    private static final String BASE_URL = "https://nominatim.openstreetmap.org/search";

    private MockRestServiceServer server;

    private NominatimClient buildClient() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new NominatimClient(builder, BASE_URL);
    }

    @Test
    void search_respostaValida_mapeiaResultadosEConverteLatLonParaDouble() {
        NominatimClient client = buildClient();
        String body = """
                [
                  {"lat":"-8.1200000","lon":"-34.9000000","display_name":"Boa Viagem, Recife, Pernambuco, Brasil"}
                ]
                """;
        server.expect(method(GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<GeocodeResultDTO> result = client.search("Boa Viagem, Recife");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getDisplayName()).isEqualTo("Boa Viagem, Recife, Pernambuco, Brasil");
        assertThat(result.get(0).getLatitude()).isEqualTo(-8.12);
        assertThat(result.get(0).getLongitude()).isEqualTo(-34.90);
    }

    @Test
    void search_semResultados_retornaListaVazia() {
        NominatimClient client = buildClient();
        server.expect(method(GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.search("lugarquenaoexiste")).isEmpty();
    }

    @Test
    void search_enviaHeaderUserAgentIdentificavel() {
        NominatimClient client = buildClient();
        server.expect(method(GET))
                .andExpect(header("User-Agent", "FlowFuel/1.0 (+https://flowfuel-api.fly.dev)"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        client.search("Boa Viagem");
    }

    @Test
    void search_nominatimRetorna500_lancaExternalServiceUnavailable() {
        NominatimClient client = buildClient();
        server.expect(method(GET)).andRespond(withServerError());

        assertThatThrownBy(() -> client.search("Boa Viagem"))
                .isInstanceOf(ExternalServiceUnavailableException.class);
    }

    @Test
    void search_latLonInvalidos_lancaExternalServiceUnavailable() {
        NominatimClient client = buildClient();
        String body = """
                [
                  {"lat":"abc","lon":"-34.90","display_name":"Lugar Estranho"}
                ]
                """;
        server.expect(method(GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.search("Boa Viagem"))
                .isInstanceOf(ExternalServiceUnavailableException.class);
    }
}
```

- [ ] **Step 2: Rodar o teste e confirmar que falha**

Run: `./mvnw test -Dtest=NominatimClientTest`

Expected: FAIL na compilação — `NominatimClient`/`GeocodeResultDTO` não existem ainda.

- [ ] **Step 3: Implementar `GeocodeResultDTO`, `NominatimResultDTO` e `NominatimClient`**

Criar `src/main/java/com/devappmobile/flowfuel/station/dto/GeocodeResultDTO.java`:

```java
package com.devappmobile.flowfuel.station.dto;

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
public class GeocodeResultDTO {
    private String displayName;
    private Double latitude;
    private Double longitude;
}
```

Criar `src/main/java/com/devappmobile/flowfuel/station/client/NominatimResultDTO.java`:

```java
package com.devappmobile.flowfuel.station.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class NominatimResultDTO {
    private String lat;
    private String lon;
    @JsonProperty("display_name")
    private String displayName;
}
```

Criar `src/main/java/com/devappmobile/flowfuel/station/client/NominatimClient.java`:

```java
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
```

- [ ] **Step 4: Rodar o teste e confirmar que passa**

Run: `./mvnw test -Dtest=NominatimClientTest`

Expected: PASS — os 5 testes verdes.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/station/dto/GeocodeResultDTO.java \
        src/main/java/com/devappmobile/flowfuel/station/client/NominatimResultDTO.java \
        src/main/java/com/devappmobile/flowfuel/station/client/NominatimClient.java \
        src/test/java/com/devappmobile/flowfuel/station/client/NominatimClientTest.java
git commit -m "feat(stations): add NominatimClient for locality geocoding"
```

---

### Task 2: `GeocodeCacheService` — cache Redis com TTL longo

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/station/GeocodeCacheService.java`
- Test: `src/test/java/com/devappmobile/flowfuel/station/GeocodeCacheServiceTest.java`

**Interfaces:**
- Consumes: `GeocodeResultDTO` (Task 1).
- Produces: `GeocodeCacheService.get(String key): Optional<List<GeocodeResultDTO>>`, `GeocodeCacheService.put(String key, List<GeocodeResultDTO> results): void`, consumidos pela Task 3.

- [ ] **Step 1: Escrever o teste que falha**

Criar `src/test/java/com/devappmobile/flowfuel/station/GeocodeCacheServiceTest.java`:

```java
package com.devappmobile.flowfuel.station;

import com.devappmobile.flowfuel.station.dto.GeocodeResultDTO;
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
class GeocodeCacheServiceTest {

    @Mock private ObjectProvider<StatefulRedisConnection<String, byte[]>> connectionProvider;
    @Mock private StatefulRedisConnection<String, byte[]> connection;
    @Mock private RedisCommands<String, byte[]> commands;

    private GeocodeCacheService cacheService;

    @BeforeEach
    void setUp() {
        cacheService = new GeocodeCacheService(connectionProvider, new ObjectMapper());
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
        GeocodeResultDTO place = GeocodeResultDTO.builder()
                .displayName("Boa Viagem, Recife, Pernambuco, Brasil")
                .latitude(-8.12).longitude(-34.90).build();
        ObjectMapper mapper = new ObjectMapper();
        byte[] serialized;
        try {
            serialized = mapper.writeValueAsBytes(List.of(place));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        when(connectionProvider.getIfAvailable()).thenReturn(connection);
        when(connection.sync()).thenReturn(commands);
        when(commands.get("key")).thenReturn(serialized);

        Optional<List<GeocodeResultDTO>> result = cacheService.get("key");

        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(1);
        assertThat(result.get().get(0).getDisplayName()).isEqualTo("Boa Viagem, Recife, Pernambuco, Brasil");
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

- [ ] **Step 2: Rodar o teste e confirmar que falha**

Run: `./mvnw test -Dtest=GeocodeCacheServiceTest`

Expected: FAIL na compilação — `GeocodeCacheService` não existe ainda.

- [ ] **Step 3: Implementar `GeocodeCacheService`**

Criar `src/main/java/com/devappmobile/flowfuel/station/GeocodeCacheService.java`:

```java
package com.devappmobile.flowfuel.station;

import com.devappmobile.flowfuel.station.dto.GeocodeResultDTO;
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
 * Cache Redis raw para resultados de geocodificacao. TTL bem mais longo
 * que StationCacheService (10min) porque coordenadas de um lugar nao
 * mudam. Fail-open: qualquer falha vira cache miss.
 */
@Service
public class GeocodeCacheService {

    private static final Logger log = LoggerFactory.getLogger(GeocodeCacheService.class);
    private static final Duration TTL = Duration.ofDays(7);
    private static final TypeReference<List<GeocodeResultDTO>> LIST_TYPE = new TypeReference<>() {};

    private final ObjectProvider<StatefulRedisConnection<String, byte[]>> connectionProvider;
    private final ObjectMapper objectMapper;

    public GeocodeCacheService(ObjectProvider<StatefulRedisConnection<String, byte[]>> connectionProvider,
            ObjectMapper objectMapper) {
        this.connectionProvider = connectionProvider;
        this.objectMapper = objectMapper;
    }

    public Optional<List<GeocodeResultDTO>> get(String key) {
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
            log.warn("Geocode cache indisponivel (get), fail-open. key={} error={}", key, e.getMessage());
            return Optional.empty();
        }
    }

    public void put(String key, List<GeocodeResultDTO> results) {
        StatefulRedisConnection<String, byte[]> connection = connectionProvider.getIfAvailable();
        if (connection == null) {
            return;
        }
        try {
            byte[] value = objectMapper.writeValueAsBytes(results);
            connection.sync().set(key, value, new SetArgs().ex(TTL.toSeconds()));
        } catch (Exception e) {
            log.warn("Geocode cache indisponivel (put), fail-open. key={} error={}", key, e.getMessage());
        }
    }
}
```

- [ ] **Step 4: Rodar o teste e confirmar que passa**

Run: `./mvnw test -Dtest=GeocodeCacheServiceTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/station/GeocodeCacheService.java \
        src/test/java/com/devappmobile/flowfuel/station/GeocodeCacheServiceTest.java
git commit -m "feat(stations): add GeocodeCacheService with 7-day TTL"
```

---

### Task 3: `StationService.geocode` — orquestração + rate limit global

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/station/StationService.java`
- Modify: `src/test/java/com/devappmobile/flowfuel/station/StationServiceTest.java`

**Interfaces:**
- Consumes: `NominatimClient.search` (Task 1), `GeocodeCacheService.get`/`put` (Task 2).
- Produces: `StationService.geocode(Long userId, String query): List<GeocodeResultDTO>`, consumido pela Task 4.

- [ ] **Step 1: Escrever os testes que falham**

Em `src/test/java/com/devappmobile/flowfuel/station/StationServiceTest.java`, adicionar os imports:

```java
import com.devappmobile.flowfuel.station.client.NominatimClient;
import com.devappmobile.flowfuel.station.dto.GeocodeResultDTO;
```

Adicionar os novos mocks, junto dos existentes:

```java
    @Mock private NominatimClient nominatimClient;
    @Mock private GeocodeCacheService geocodeCacheService;
```

Atualizar `setUp()` pra passar as duas novas dependências no construtor:

```java
    @BeforeEach
    void setUp() {
        stationService = new StationService(overpassClient, openChargeMapClient, cacheService,
                proxyManagerProvider, nominatimClient, geocodeCacheService);
    }
```

Adicionar os novos testes, ao final da classe (antes do `}` de fechamento):

```java
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
```

- [ ] **Step 2: Rodar os testes e confirmar que falham**

Run: `./mvnw test -Dtest=StationServiceTest`

Expected: FAIL na compilação — `StationService.geocode` não existe, construtor não bate com o número de argumentos.

- [ ] **Step 3: Implementar `StationService.geocode`**

Em `src/main/java/com/devappmobile/flowfuel/station/StationService.java`, adicionar os imports:

```java
import com.devappmobile.flowfuel.station.client.NominatimClient;
import com.devappmobile.flowfuel.station.dto.GeocodeResultDTO;
import java.util.Locale;
```

(`Locale` já está importado — confirmar antes de duplicar.)

Adicionar os dois novos campos e atualizar o construtor:

```java
    private final NominatimClient nominatimClient;
    private final GeocodeCacheService geocodeCacheService;

    public StationService(OverpassClient overpassClient, OpenChargeMapClient openChargeMapClient,
            StationCacheService cacheService, ObjectProvider<ProxyManager<String>> proxyManagerProvider,
            NominatimClient nominatimClient, GeocodeCacheService geocodeCacheService) {
        this.overpassClient = overpassClient;
        this.openChargeMapClient = openChargeMapClient;
        this.cacheService = cacheService;
        this.proxyManagerProvider = proxyManagerProvider;
        this.nominatimClient = nominatimClient;
        this.geocodeCacheService = geocodeCacheService;
    }
```

Adicionar a constante do rate limit global e os dois métodos novos, logo após `findNearby`:

```java
    private static final BucketConfiguration NOMINATIM_GLOBAL_RATE_LIMIT = BucketConfiguration.builder()
            .addLimit(Bandwidth.builder().capacity(1).refillGreedy(1, Duration.ofSeconds(1)).build())
            .build();

    public List<GeocodeResultDTO> geocode(Long userId, String query) {
        checkRateLimit(userId);
        checkGeocodeGlobalRateLimit();

        String cacheKey = "stations:geocode:" + query.trim().toLowerCase(Locale.forLanguageTag("pt-BR"));
        Optional<List<GeocodeResultDTO>> cached = geocodeCacheService.get(cacheKey);
        if (cached.isPresent()) {
            return cached.get();
        }

        List<GeocodeResultDTO> results = nominatimClient.search(query.trim());
        geocodeCacheService.put(cacheKey, results);
        return results;
    }

    private void checkGeocodeGlobalRateLimit() {
        ProxyManager<String> proxyManager = proxyManagerProvider.getIfAvailable();
        if (proxyManager == null) {
            return;
        }
        ConsumptionProbe probe;
        try {
            BucketProxy bucket = proxyManager.builder().build("nominatim-global-rate-limit", () -> NOMINATIM_GLOBAL_RATE_LIMIT);
            probe = bucket.tryConsumeAndReturnRemaining(1);
        } catch (Exception e) {
            log.warn("Rate limit global do Nominatim indisponivel, fail-open. error={}", e.getMessage());
            return;
        }
        if (!probe.isConsumed()) {
            long retryAfterSeconds = Math.max(1L,
                    Math.ceilDiv(probe.getNanosToWaitForRefill(), 1_000_000_000L));
            throw new RateLimitExceededException(retryAfterSeconds);
        }
    }
```

- [ ] **Step 4: Rodar os testes e confirmar que passam**

Run: `./mvnw test -Dtest=StationServiceTest`

Expected: PASS — todos os testes de `StationServiceTest` (existentes + os 5 novos) verdes.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/station/StationService.java \
        src/test/java/com/devappmobile/flowfuel/station/StationServiceTest.java
git commit -m "feat(stations): orchestrate geocode with global + per-user rate limits"
```

---

### Task 4: `StationController.geocode` — endpoint HTTP + config

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/station/StationController.java`
- Modify: `src/main/resources/application.properties`
- Modify: `src/test/java/com/devappmobile/flowfuel/station/StationControllerIntegrationTest.java`

**Interfaces:**
- Consumes: `StationService.geocode` (Task 3).
- Produces: `GET /stations/geocode?query=...` — consumido pelo sub-projeto C (Android, spec/plano separado).

- [ ] **Step 1: Escrever os testes que falham**

Em `src/test/java/com/devappmobile/flowfuel/station/StationControllerIntegrationTest.java`, adicionar o import:

```java
import com.devappmobile.flowfuel.station.dto.GeocodeResultDTO;
```

Adicionar os novos testes, ao final da classe:

```java
    @Test
    void geocode_semToken_retorna401() throws Exception {
        mockMvc.perform(get("/api/v1/stations/geocode").param("query", "Boa Viagem"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void geocode_queryCurtaDemais_retorna400() throws Exception {
        String token = obterToken("station-geo1@test.com");
        mockMvc.perform(get("/api/v1/stations/geocode")
                        .header("Authorization", "Bearer " + token)
                        .param("query", "ab"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void geocode_requisicaoValida_retorna200ComResultadosDoService() throws Exception {
        String token = obterToken("station-geo2@test.com");
        when(stationService.geocode(anyLong(), anyString()))
                .thenReturn(List.of(GeocodeResultDTO.builder()
                        .displayName("Boa Viagem, Recife, Pernambuco, Brasil")
                        .latitude(-8.12).longitude(-34.90).build()));

        mockMvc.perform(get("/api/v1/stations/geocode")
                        .header("Authorization", "Bearer " + token)
                        .param("query", "Boa Viagem, Recife"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].displayName").value("Boa Viagem, Recife, Pernambuco, Brasil"))
                .andExpect(jsonPath("$[0].latitude").value(-8.12))
                .andExpect(jsonPath("$[0].longitude").value(-34.90));
    }
```

(`anyLong`, `anyString` já vêm do `import static org.mockito.ArgumentMatchers.*;` existente no arquivo.)

- [ ] **Step 2: Rodar os testes e confirmar que falham**

Run: `./mvnw test -Dtest=StationControllerIntegrationTest`

Expected: FAIL — `stationService.geocode(...)` não existe no mock (compila, mas o endpoint `/geocode` ainda não existe no controller, então as chamadas retornam 404 em vez do esperado).

- [ ] **Step 3: Implementar o endpoint e a configuração**

Em `src/main/java/com/devappmobile/flowfuel/station/StationController.java`, adicionar os imports:

```java
import com.devappmobile.flowfuel.station.dto.GeocodeResultDTO;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
```

Adicionar o método, logo após `getNearbyStations`:

```java
    @GetMapping("/geocode")
    public List<GeocodeResultDTO> geocode(
            @AuthenticationPrincipal User user,
            @RequestParam @NotBlank @Size(min = 3, max = 100) String query) {
        return stationService.geocode(user.getId(), query);
    }
```

Em `src/main/resources/application.properties`, adicionar logo após as linhas existentes de `flowfuel.station.*`:

```properties
flowfuel.station.nominatim.base-url=${NOMINATIM_BASE_URL:https://nominatim.openstreetmap.org/search}
```

- [ ] **Step 4: Rodar os testes e confirmar que passam**

Run: `./mvnw test -Dtest=StationControllerIntegrationTest`

Expected: PASS — todos os testes de `StationControllerIntegrationTest` (existentes + os 3 novos) verdes.

- [ ] **Step 5: Rodar a suíte completa do módulo `station`**

Run: `./mvnw test -Dtest="com.devappmobile.flowfuel.station.**"`

Expected: PASS — nenhuma regressão em `StationServiceTest`, `StationCacheServiceTest`, `OverpassClientTest`, `OpenChargeMapClientTest`, `HaversineUtilTest`, `StationRedisIntegrationTest`.

- [ ] **Step 6: Verificação manual**

Com o backend rodando localmente (`./mvnw spring-boot:run`), obter um token válido e chamar:

```bash
curl -H "Authorization: Bearer <token>" "http://localhost:8090/api/v1/stations/geocode?query=Boa%20Viagem"
```

Confirmar que retorna uma lista de candidatos com `displayName` incluindo cidade/estado suficiente pra desambiguar (ex: "Boa Viagem, Recife, Pernambuco, Brasil"), e que uma consulta sem match (`query=zzzzznaoexiste`) retorna `[]` com 200, não erro.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/station/StationController.java \
        src/main/resources/application.properties \
        src/test/java/com/devappmobile/flowfuel/station/StationControllerIntegrationTest.java
git commit -m "feat(stations): expose GET /stations/geocode endpoint"
```
