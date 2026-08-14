# Design: Endpoint de geocodificação para busca de postos por localidade

**Data:** 2026-08-14
**Status:** Aprovado

## Contexto

Sub-projeto B de uma decomposição maior (feature de pesquisa de
localidade em Postos no app Android, ver conversa 2026-08-14 no repo
`flowfuel-app`). Hoje `GET /stations/nearby` só aceita lat/lng — o app
sempre usa a localização atual do dispositivo (GPS). Não existe forma de
buscar postos perto de um lugar que não seja onde o usuário está agora.

Caso de uso real do produto (exemplo do próprio usuário): "estou no
Cordeiro, Recife, preciso ir pra Boa Viagem, Recife, quero saber os
postos de Boa Viagem" — ou seja, busca por **bairro ou cidade**, não só
cidade. Nomes de bairro/cidade são ambíguos no Brasil (várias "Boa
Viagem", "Santa Rita" etc. em estados diferentes), então a resposta
precisa trazer contexto suficiente (cidade, estado) pra o usuário
escolher o lugar certo antes de buscar postos.

## Objetivo

Novo endpoint `GET /stations/geocode?query={texto}` que resolve texto
livre (bairro ou cidade) em até 5 candidatos com nome completo e
coordenadas, via proxy do Nominatim (geocodificador do OpenStreetMap,
gratuito, sem key) — mesmo espírito de "grátis via proxy próprio" já
usado pra Overpass (postos de combustível) e Open Charge Map (recarga
elétrica), documentado em
`docs/superpowers/specs/2026-07-01-postos-proximos-backend-design.md`.

## Escopo

Só o backend (`station/` package). Não inclui o consumo no app Android
(sub-projeto C, spec/plano separado no repo `flowfuel-app`) nem mudança
nenhuma em `/stations/nearby`.

---

## Design

### Contrato da API

```
GET /stations/geocode?query=Boa Viagem, Recife
Authorization: Bearer <token>
```

Resposta (200, lista vazia se não achar nada — mesma convenção de
`/stations/nearby` quando não há postos):

```json
[
  { "displayName": "Boa Viagem, Recife, Pernambuco, Brasil", "latitude": -8.12, "longitude": -34.90 },
  { "displayName": "Boa Viagem, Niterói, Rio de Janeiro, Brasil", "latitude": -22.90, "longitude": -43.09 }
]
```

`query`: `@NotBlank @Size(min = 3, max = 100)`, mesmo estilo de validação
já usado em `StationController` (`@NotNull`, `@DecimalMin`/`@DecimalMax`
no `/nearby`).

### `StationController`

Novo método, mesmo padrão do `getNearbyStations`:

```java
@GetMapping("/geocode")
public List<GeocodeResultDTO> geocode(
        @AuthenticationPrincipal User user,
        @RequestParam @NotBlank @Size(min = 3, max = 100) String query) {
    return stationService.geocode(user.getId(), query);
}
```

### `GeocodeResultDTO`

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

(Mesmo conjunto de anotações Lombok de `StationResponseDTO` — o projeto
não usa `@Value`, prefere `@Getter @Setter @NoArgsConstructor
@AllArgsConstructor @Builder` em todos os DTOs de resposta.)

### `NominatimClient` (novo, `station/client/`)

Mesmo molde de `OverpassClient`/`OpenChargeMapClient` — `RestClient`,
`@Value` pra base URL, `User-Agent` obrigatório, exceção padrão em falha:

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
 * de uso restritiva — ver checkRateLimit em StationService (limite global,
 * nao so por usuario). Ver docs/superpowers/specs/2026-08-14-station-geocode-design.md.
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

`NominatimResultDTO` (novo, `station/client/`) — só os campos usados:

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

(Mesmo estilo `@Getter @Setter` simples de `OpenChargeMapPoiDTO` — DTOs
que só desserializam resposta de terceiro não usam `@Builder`/`@Data`
neste projeto.)

**Atenção na implementação:** `lat`/`lon` do Nominatim vêm como **string**
no JSON, não número — por isso o parse manual em `NominatimClient`, e por
isso `NominatimResultDTO` declara os dois como `String`. O projeto **não**
tem `PropertyNamingStrategies.SNAKE_CASE` global configurado (confirmado
— nenhuma ocorrência no código), então `display_name` precisa do
`@JsonProperty` explícito acima, igual `OpenChargeMapPoiDTO` já faz pra
seus campos (`@JsonProperty("ID")`, `@JsonProperty("AddressInfo")`).

### `StationService.geocode(userId, query)`

Mesma orquestração de `findNearby`: rate limit → cache → client → cache
put. Diferença chave: **dois rate limits**, não um.

```java
public List<GeocodeResultDTO> geocode(Long userId, String query) {
    checkRateLimit(userId);       // já existe — 10/min por usuário
    checkGeocodeGlobalRateLimit(); // novo — 1/seg agregado, ver abaixo

    String cacheKey = "stations:geocode:" + query.trim().toLowerCase(Locale.forLanguageTag("pt-BR"));
    Optional<List<GeocodeResultDTO>> cached = geocodeCacheService.get(cacheKey);
    if (cached.isPresent()) {
        return cached.get();
    }

    List<GeocodeResultDTO> results = nominatimClient.search(query.trim());
    geocodeCacheService.put(cacheKey, results);
    return results;
}
```

**Rate limit global — ponto crítico:** a política de uso do Nominatim
público exige **no máximo 1 requisição/segundo, agregada em todo o
servidor** — bem mais restritiva que o limite de 10/min *por usuário* já
existente em `checkRateLimit`. Estourar essa cota arrisca o IP do backend
ser bloqueado pelo Nominatim, o que quebraria este endpoint pra todo
mundo (não só pro usuário que abusou). Novo bucket com chave **fixa**
(não por `userId`), reaproveitando o mesmo `ProxyManager<String>`/Bucket4j
já usado em `checkRateLimit`, mesmo fail-open se o Redis estiver
indisponível:

```java
private static final BucketConfiguration NOMINATIM_GLOBAL_RATE_LIMIT = BucketConfiguration.builder()
        .addLimit(Bandwidth.builder().capacity(1).refillGreedy(1, Duration.ofSeconds(1)).build())
        .build();

private void checkGeocodeGlobalRateLimit() {
    ProxyManager<String> proxyManager = proxyManagerProvider.getIfAvailable();
    if (proxyManager == null) {
        return; // fail-open, mesmo padrão de checkRateLimit
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

Se o rate limit global disparar, o usuário recebe o mesmo erro 429 já
tratado hoje pelo `RateLimitExceededException` — nenhum handler novo
necessário.

### `GeocodeCacheService` (novo, `station/`)

Mesmo padrão Redis raw fail-open de `StationCacheService`, TTL bem mais
longo (endereço não muda) e tipo de valor diferente:

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

### Configuração

Em `application.properties`, junto das outras entradas de `station`:

```properties
flowfuel.station.nominatim.base-url=${NOMINATIM_BASE_URL:https://nominatim.openstreetmap.org/search}
```

Sem API key — Nominatim público não exige uma (diferente do Open Charge
Map).

## Erros / edge cases

- `query` vazio/curto demais (`< 3` chars): 400, validação Bean Validation
  já rejeita antes de chegar no service — mesmo padrão do `/nearby`.
- Nominatim fora do ar: `ExternalServiceUnavailableException` → 503
  (`ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE`), mesmo handler genérico de
  `AppException` já existente em `GlobalExceptionHandler` — nenhum
  handler novo necessário.
- Nenhum resultado encontrado: lista vazia, 200 — **não** é erro.
- Rate limit (usuário ou global) estourado: `RateLimitExceededException`
  → 429, já tratado.
- Redis indisponível: fail-open tanto pro cache quanto pro rate limit
  global — comportamento idêntico ao `/nearby` hoje.

## Testes

- Teste unitário de `StationService.geocode`: cache hit não chama o
  client; cache miss chama o client e popula o cache; rate limit por
  usuário e rate limit global disparando `RateLimitExceededException`
  cada um isoladamente; Nominatim falhando propaga
  `ExternalServiceUnavailableException`.
- Teste unitário de `NominatimClient`: parse de `lat`/`lon` string → 
  Double; resposta vazia `[]`; `NumberFormatException` em `lat`/`lon`
  inválido também vira `ExternalServiceUnavailableException`.
- Teste de integração do `StationController` (`@WebMvcTest` ou
  equivalente já usado nos outros controllers do projeto): 200 com lista,
  400 pra `query` curta demais, 401 sem token.
- Verificação manual: `curl` contra o endpoint local com uma consulta
  ambígua real (ex: "Boa Viagem") e conferir que os `displayName`
  retornados realmente desambiguam (cidade/estado presentes).

## Fora do escopo

- Consumo no app Android (sub-projeto C, spec/plano separado no repo
  `flowfuel-app`)
- Autocomplete/busca conforme o usuário digita (debounce, etc.) — decisão
  de UX que pertence ao sub-projeto C, não afeta o contrato deste
  endpoint
- Geocodificação reversa (lat/lng → endereço) — não é o caso de uso aqui
- Suporte a países além do Brasil (`countrycodes=br` fixo)
