# Postos Próximos — Backend (GET /stations/nearby)

## Contexto

O app Android está implementando uma tela "Postos" que busca postos de
combustível e estações de recarga elétrica próximos da localização do
usuário (spec e plano no repositório `flowfuel-app`:
`docs/superpowers/specs/2026-07-01-postos-proximos-design.md` e
`docs/superpowers/plans/2026-07-01-postos-proximos.md`). O app já está
pronto e integrado contra o contrato abaixo — hoje ele recebe erro de rede
porque o endpoint não existe. Esta é a implementação desse endpoint.

**Revisado em 2026-07-01:** a primeira versão deste spec desenhava a
integração em cima do Google Places API (New). Decisão trocada para evitar
custo recorrente e a necessidade de billing/cartão no Google Cloud só para
um MVP — o backend agora combina duas fontes de dados **gratuitas**:
OpenStreetMap (via Overpass API) para postos de combustível, e Open Charge
Map para estações de recarga elétrica. Trade-off aceito: sem `rating`
confiável e cobertura de dados menor que a base comercial do Google (ok
para o escopo do MVP).

**Revisado em 2026-07-01 (2ª vez), pós-implementação:** teste manual contra
os servidores reais (fora dos testes automatizados, que usam
`MockRestServiceServer`) mostrou que a Open Charge Map **passou a exigir
API key em toda chamada** (`403 Forbidden: "You must specify an API key
using the key query parameter or x-api-key header."`), não apenas para
aumentar o rate limit como assumido originalmente neste spec e replicado
no comentário Javadoc de `OpenChargeMapClient`. Overpass continua sem key
obrigatória e foi validado retornando dados reais de postos de
combustível (com timeouts 504 ocasionais — instância pública sobrecarregada,
ver seção de Riscos). Efeito prático: **sem `OPEN_CHARGE_MAP_API_KEY`
configurada, `type: ELECTRIC` nunca aparece nos resultados** — o endpoint
continua funcionando normalmente (200 só com postos de combustível via
Overpass), já que a falha da OCM é tratada como fonte parcial indisponível,
não como erro. Ver seção "Provisionamento" atualizada abaixo.

Decisão de segurança mantida do lado Android: nenhuma chamada a serviço
externo é feita pelo app — tudo passa pelo endpoint próprio, mesmo as
fontes sendo públicas, para manter o app desacoplado do provider de dados
(trocar de fonte no futuro não exige alterar o app).

## Objetivo

Implementar `GET /api/v1/stations/nearby` retornando postos de combustível
e estações de recarga elétrica próximos de um ponto, combinando uma busca
na Overpass API (OpenStreetMap) e uma na Open Charge Map API, com cache e
rate limiting para proteger os servidores públicos dessas fontes de uso
excessivo (não por custo — ambas são gratuitas).

## Fora de escopo

- Preço de combustível (nenhuma das duas fontes fornece; ver spec do
  Android).
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
  "rating": null,          // nullable — OSM/Open Charge Map raramente têm nota
  "latitude": -8.05,
  "longitude": -34.90
}

400 — lat/lng ausentes ou fora de faixa (-90..90 / -180..180)   → VALIDATION_FAILED
401 — sem token / token inválido                                → AUTH_* (padrão existente)
429 — rate limit por usuário excedido                            → RATE_LIMIT_EXCEEDED
503 — falha ao chamar Overpass ou Open Charge Map (timeout, erro HTTP) → EXTERNAL_SERVICE_UNAVAILABLE (novo)
```

`placeId` passa a ser prefixado pela origem, já que agora há duas fontes
distintas: `osm:{type}/{id}` (ex.: `osm:node/123456789`) para postos de
combustível, `ocm:{id}` para estações de recarga.

## Arquitetura

Segue o padrão "package por feature" já usado em `vehicle`, `vehicleevent`
etc.: novo pacote `com.devappmobile.flowfuel.station` com
`StationController` → `StationService` → `OverpassClient` +
`OpenChargeMapClient`.

**Por que RestClient, não WebClient:** a aplicação usa
`spring-boot-starter-web` (MVC síncrono), sem `spring-boot-starter-webflux`.
`RestClient` (Spring 6.1+, incluso no Boot 3.5.7 já usado no projeto) é a
opção síncrona idiomática, sem dependência nova.

**Chamada à Overpass API (postos de combustível):**
`POST https://overpass-api.de/api/interpreter`, corpo em Overpass QL:

```
[out:json][timeout:10];
nwr(around:{radius},{lat},{lng})[amenity=fuel];
out center;
```

Sem autenticação/key. Resposta traz elementos OSM (`node`/`way`/`relation`)
com `tags.name` e coordenadas (`lat`/`lon` direto em nós, ou `center` em
ways/relations).

**Chamada à Open Charge Map API (recarga elétrica):**
`GET https://api.openchargemap.io/v3/poi?latitude={lat}&longitude={lng}&distance={radiusKm}&distanceunit=KM&maxresults=20`,
header `X-API-Key`. **Correção pós-implementação (2026-07-01):** ao contrário
do assumido originalmente, a API hoje **exige** essa key em toda chamada —
sem ela retorna `403 Forbidden`, não um rate limit reduzido. O código
(`OpenChargeMapClient`) já trata isso corretamente como falha de fonte
(fail-partial, não fail-total — ver `ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE`
abaixo), então a ausência da key não derruba o endpoint, só zera os
resultados `type: ELECTRIC`. Resposta (quando a key está presente) traz
`ID`, `AddressInfo.Title`, `AddressInfo.Latitude/Longitude`.

As duas chamadas são feitas sequencialmente (Overpass primeiro, depois
Open Charge Map) e os resultados mesclados numa única lista — mesma
simplicidade da versão anterior do design; latência combinada aceitável
dado que a tela já mostra skeleton de loading.

**Distância:** nem Overpass nem Open Charge Map devolvem distância no
formato que o app espera de forma consistente entre as duas fontes.
Calculada no backend via fórmula de Haversine, usando `lat`/`lng` da query
(posição do usuário) e a coordenada de cada resultado, para os dois casos —
mantém a lógica uniforme independente da fonte.

**Novo ErrorCode:**
`EXTERNAL_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Serviço externo indisponível")`
em `common/error/ErrorCode.java`, usado quando a chamada à Overpass ou à
Open Charge Map falha (timeout, HTTP 4xx/5xx) — mapeado para 503, distinto
de `INTERNAL_ERROR` para deixar claro em logs/Sentry que a causa é uma
dependência externa. Falha de uma das duas fontes não derruba a outra: se
Overpass falhar mas Open Charge Map responder (ou vice-versa), retorna 200
só com os resultados da fonte que funcionou; só retorna 503 se as duas
falharem.

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
  segue direto para Overpass/Open Charge Map. Mesma filosofia do
  `RateLimitFilter` existente.
- **Por que o cache continua existindo mesmo sem custo por chamada:**
  Overpass e Open Charge Map são gratuitas, mas os servidores públicos têm
  política de uso justo e podem throttlar/bloquear IPs com volume alto —
  o cache reduz a chance de bater nesse limite, não é sobre economizar
  dinheiro.

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
  bater no cache/Overpass/Open Charge Map; estourou → lança
  `AppException(ErrorCode.RATE_LIMIT_EXCEEDED)` (mesmo padrão de erro 429 +
  `Retry-After` já usado nos endpoints de auth).
- **Fail-open** também aqui se o Redis cair — não bloqueia o usuário por
  causa de infraestrutura.
- **Por quê manter o rate limit sem custo por chamada:** protege os
  servidores públicos do Overpass (fair-use policy) e o limite de
  requisições do Open Charge Map contra abuso vindo do nosso backend, não
  evita gasto financeiro.

## Plano de testes

- **Unitário — `StationService`:** `RestClient` mockado (`OverpassClient` e
  `OpenChargeMapClient`) — merge de fuel + electric, cálculo de Haversine,
  ordenação por distância, fallback parcial (uma fonte falha, a outra
  responde → 200 só com o que funcionou) e fallback total
  (`EXTERNAL_SERVICE_UNAVAILABLE`) quando as duas falham.
- **Unitário — cache:** segunda chamada com o mesmo lat/lng arredondado não
  bate nos clients (`Mockito.verify(..., times(1))` após duas chamadas
  ao service).
- **Unitário — rate limit:** 11ª chamada no mesmo minuto (mesmo `userId`)
  lança `AppException` com `ErrorCode.RATE_LIMIT_EXCEEDED`.
- **Integração (Testcontainers Redis, padrão do `RateLimitFilterIntegrationTest`):**
  fail-open quando Redis está fora do ar — endpoint continua funcionando
  sem cache e sem rate limit.
- **Controller (`MockMvc`):** 401 sem token; 400 com `lat`/`lng` ausentes
  ou fora de faixa; 200 com resposta no formato esperado (mock do service).

## Provisionamento (pré-requisito, fora do código)

1. Overpass API é pública e não exige key — nenhum cadastro necessário para
   postos de combustível.
2. **Open Charge Map exige key (correção pós-implementação, 2026-07-01
   — ver seção "Arquitetura" acima):** registrar conta gratuita em
   openchargemap.org, gerar uma API key e configurar como variável de
   ambiente `OPEN_CHARGE_MAP_API_KEY`. A aplicação continua subindo e o
   endpoint continua respondendo 200 sem essa variável (`@Value(...:"")`,
   igual ao design original), mas **sem ela nenhuma estação `ELECTRIC`
   aparece nos resultados** — na prática, hoje é obrigatória para a feature
   funcionar por completo em produção, mesmo não sendo obrigatória para o
   processo subir (diferente do padrão de secret bloqueante usado em
   `jwt.secret=${JWT_SECRET}`).
3. Nenhuma conta de billing/cartão de crédito é necessária — diferença
   central em relação à versão anterior deste spec (Google Places).
4. Antes de tráfego de produção real, avaliar se vale rodar uma instância
   própria do Overpass (imagem Docker oficial) em vez de depender só do
   servidor público `overpass-api.de`, para não ficar sujeito ao fair-use
   compartilhado.

## Riscos / Pontos de atenção

- **Open Charge Map exige key (descoberto pós-implementação, não estava no
  design original):** sem `OPEN_CHARGE_MAP_API_KEY` configurada em
  produção, toda chamada à OCM falha com 403 e o endpoint silenciosamente
  nunca retorna `type: ELECTRIC` — sem erro visível para o usuário nem
  alerta, só ausência de dados. Gerar a key é gratuito; ver "Provisionamento".
- **Fair-use dos servidores públicos, não custo:** Overpass e Open Charge
  Map são gratuitas, mas sem SLA — uso muito acima do esperado pode gerar
  throttling/bloqueio temporário de IP no Overpass público. Cache e rate
  limit mitigam isso; se o app tiver tração, revisitar TTL do cache e
  considerar instância própria do Overpass.
- **Qualidade/cobertura de dados:** OSM é colaborativo — cobertura de
  postos pode ter buracos em cidades menores, comparado à base comercial
  do Google. `Empty` state do app já cobre esse caso.
- **Sem `rating` confiável:** nem OSM nem Open Charge Map têm nota
  equivalente à do Google Places — campo fica `null` na quase totalidade
  dos resultados, o app já trata isso como opcional.
- **Sem persistência:** se o Redis for perdido (restart, falha), o cache
  zera e a próxima rodada de requisições bate direto nos servidores
  públicos de novo — trade-off aceito ao não persistir em Postgres.
- **Formato de resposta da Overpass:** `way`/`relation` não trazem
  `lat`/`lon` diretos, só via `out center` — confirmar que o parser trata
  os três tipos de elemento (`node`, `way`, `relation`) antes de assumir
  que todo resultado tem coordenada direta.
