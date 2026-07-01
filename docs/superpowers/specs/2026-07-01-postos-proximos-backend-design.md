# Postos Próximos — Backend (GET /stations/nearby)

## Contexto

O app Android está implementando uma tela "Postos" que busca postos de
combustível e estações de recarga elétrica próximos da localização do
usuário (spec e plano no repositório `flowfuel-app`:
`docs/superpowers/specs/2026-07-01-postos-proximos-design.md` e
`docs/superpowers/plans/2026-07-01-postos-proximos.md`). O app já está
pronto e integrado contra o contrato abaixo — hoje ele recebe erro de rede
porque o endpoint não existe. Esta é a implementação desse endpoint.

Decisão de segurança já tomada no lado Android: a API key do Google Places
**nunca** fica no app — o backend proxeia a chamada ao Google e guarda a
key só no servidor.

## Objetivo

Implementar `GET /api/v1/stations/nearby` retornando postos de combustível
e estações de recarga elétrica próximos de um ponto, combinando duas
buscas no Google Places API (New), com cache e rate limiting para conter o
custo por chamada paga ao Google.

## Fora de escopo

- Preço de combustível (Google Places não fornece; ver spec do Android).
- "Ver detalhes" do posto (endpoint de detalhes do place).
- Persistência em Postgres dos resultados — cache é só Redis, efêmero.
- Cache de segundo nível / Spring Cache abstraction (`@Cacheable`) — essa
  infra (`spring-boot-starter-data-redis`, `CacheConfig`) foi desenhada em
  `2026-06-25-redis-cache-dashboard-design.md` mas **ainda não foi
  implementada** no código; esta feature não depende dela e não a implementa.

## Contrato da API

```
GET /api/v1/stations/nearby?lat={double}&lng={double}&radius={int, default 5000}
Autenticação: obrigatória (Bearer JWT, como todo o resto da API)

200 OK — lista de:
{
  "placeId": "string",
  "name": "string",
  "type": "FUEL" | "ELECTRIC",
  "distanceMeters": 420,
  "rating": 4.6,           // nullable
  "latitude": -8.05,
  "longitude": -34.90
}

400 — lat/lng ausentes ou fora de faixa (-90..90 / -180..180)   → VALIDATION_FAILED
401 — sem token / token inválido                                → AUTH_* (padrão existente)
429 — rate limit por usuário excedido                            → RATE_LIMIT_EXCEEDED
503 — falha ao chamar o Google Places (timeout, erro HTTP, key inválida) → EXTERNAL_SERVICE_UNAVAILABLE (novo)
```

## Arquitetura

Segue o padrão "package por feature" já usado em `vehicle`, `vehicleevent`
etc.: novo pacote `com.devappmobile.flowfuel.station` com
`StationController` → `StationService` → `PlacesClient`.

**Por que RestClient, não WebClient:** a aplicação usa
`spring-boot-starter-web` (MVC síncrono), sem `spring-boot-starter-webflux`.
`RestClient` (Spring 6.1+, incluso no Boot 3.5.7 já usado no projeto) é a
opção síncrona idiomática, sem dependência nova.

**Chamada ao Google Places (New):**
`POST https://places.googleapis.com/v1/places:searchNearby`, headers
`X-Goog-Api-Key` e `X-Goog-FieldMask: places.id,places.displayName,places.location,places.rating`
(field mask restrito ao tier "Pro" — evita puxar campos caros como fotos
e horário de funcionamento, que subiriam o custo por chamada).

Duas chamadas sequenciais — uma com `includedTypes: ["gas_station"]`,
outra com `includedTypes: ["electric_vehicle_charging_station"]` — merge
das duas listas. Sequencial em vez de paralelo (`CompletableFuture`) por
simplicidade; latência combinada (~200-400ms por chamada) é aceitável dado
que a tela já mostra skeleton de loading.

**Distância:** Places API (New) não devolve distância pronta. Calculada
no backend via fórmula de Haversine, usando `lat`/`lng` da query (posição
do usuário) e o `location` de cada place retornado.

**Novo ErrorCode:**
`EXTERNAL_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Serviço externo indisponível")`
em `common/error/ErrorCode.java`, usado quando a chamada ao Google Places
falha (timeout, HTTP 4xx/5xx do Google, key inválida/sem billing) — mapeado
para 503, distinto de `INTERNAL_ERROR` para deixar claro em logs/Sentry
que a causa é uma dependência externa.

## Cache (Redis)

- **Chave:** `stations:nearby:{latArredondado}:{lngArredondado}:{radius}`
  — lat/lng arredondados para 3 casas decimais (~111m de grid).
- **Valor:** lista de resultados (já mesclada e ordenada) serializada em
  JSON (Jackson).
- **TTL:** 10 minutos.
- **Implementação:** novo `StationCacheService` com `get(key): List<Station>?`
  / `put(key, list)`, usando a mesma conexão Lettuce (`StatefulRedisConnection`)
  já configurada em `RateLimitingConfig` para o Bucket4j — sem
  `@Cacheable`, sem `spring-boot-starter-data-redis` novo. Evita também o
  risco de ordenação autorização-vs-cache que o spec do cache de dashboard
  identificou: aqui não existe esse risco porque a chave é puramente
  geográfica, não por `userId` — não há dado por-usuário para vazar entre
  usuários diferentes.
- **Fail-open:** Redis indisponível → cache miss silencioso (loga warning),
  segue direto para o Google Places. Mesma filosofia do `RateLimitFilter`
  existente.

## Rate limiting (Bucket4j, por usuário)

- **Limite:** 10 requisições/minuto por `userId` autenticado.
- **Por que não reaproveitar o `RateLimitFilter` existente:** ele é
  por IP, roda **antes** da autenticação (`FILTER_ORDER` =
  `HIGHEST_PRECEDENCE + 20`, antes do `JwtAuthenticationFilter`), e só
  intercepta `POST` (`shouldNotFilter` retorna `true` para qualquer
  método que não seja POST) — `GET /stations/nearby` fica estruturalmente
  fora do escopo desse filtro.
- **Implementação:** checagem dentro de `StationService`, não um `Filter`
  novo — reaproveita o bean `ProxyManager<String>` (`LettuceBasedProxyManager`)
  já configurado em `RateLimitingConfig`, só com prefixo de chave
  diferente: `station-rate-limit::{userId}`. `tryConsume(1)` antes de
  bater no cache/Places; estourou → lança `AppException(ErrorCode.RATE_LIMIT_EXCEEDED)`
  (mesmo padrão de erro 429 + `Retry-After` já usado nos endpoints de auth).
- **Fail-open** também aqui se o Redis cair — não bloqueia o usuário por
  causa de infraestrutura.

## Plano de testes

- **Unitário — `StationService`:** `RestClient` mockado — merge de fuel +
  electric, cálculo de Haversine, ordenação por distância, fallback
  (`EXTERNAL_SERVICE_UNAVAILABLE`) quando o Google retorna erro/timeout.
- **Unitário — cache:** segunda chamada com o mesmo lat/lng arredondado não
  bate no `RestClient` (`Mockito.verify(..., times(1))` após duas chamadas
  ao service).
- **Unitário — rate limit:** 11ª chamada no mesmo minuto (mesmo `userId`)
  lança `AppException` com `ErrorCode.RATE_LIMIT_EXCEEDED`.
- **Integração (Testcontainers Redis, padrão do `RateLimitFilterIntegrationTest`):**
  fail-open quando Redis está fora do ar — endpoint continua funcionando
  sem cache e sem rate limit.
- **Controller (`MockMvc`):** 401 sem token; 400 com `lat`/`lng` ausentes
  ou fora de faixa; 200 com resposta no formato esperado (mock do service).

## Provisionamento (pré-requisito, fora do código)

1. Criar projeto no Google Cloud Console, habilitar billing.
2. Habilitar "Places API (New)".
3. Gerar API key, restringir por IP do servidor (chamada é server-to-server,
   não do app — restrição por IP é mais apropriada que por app/bundle ID).
4. Configurar `GOOGLE_PLACES_API_KEY` como variável de ambiente — segue o
   padrão de secret obrigatório sem fallback já usado (`jwt.secret=${JWT_SECRET}`,
   sem default): sem a env var, a aplicação não sobe.
5. Em dev/testes, os testes unitários (Places mockado) passam sem a key
   configurada; o endpoint real (`curl`/app apontando pro backend local)
   retornará 503 até alguém configurar a key.

## Riscos / Pontos de atenção

- **Custo do Google Places:** mesmo com cache e rate limit, cada combinação
  nova de região gera 2 chamadas pagas (fuel + electric). Se o app tiver
  tração, vale revisitar o TTL do cache (10min pode ser curto/longo demais
  dependendo do volume real) e considerar um raio de bucket maior que 3
  casas decimais.
- **Field mask do Places (New):** o tier de billing (Essentials/Pro/Enterprise)
  depende exatamente dos campos pedidos no `X-Goog-FieldMask`. `rating`
  entra no tier "Pro", mais caro que "Essentials IDs Only". Se o custo for
  um problema, dá para reavaliar se vale manter `rating` no MVP.
  `[INFERIDO — confirmar preço exato na tabela de billing do Google antes
  de estimar custo real]`.
- **Sem persistência:** se o Redis for perdido (restart, falha), o cache
  zera e a próxima rodada de requisições gera chamadas pagas de novo — é o
  trade-off aceito ao não persistir em Postgres.
- **Places API (New) é relativamente recente:** vale confirmar que
  `electric_vehicle_charging_station` é um `includedType` válido na
  documentação atual do Google antes de implementar — nomes de tipo já
  mudaram entre versões da API no passado.
