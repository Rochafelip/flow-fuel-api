# Design: Integração Google Places API (New) para busca de postos de combustível

**Data**: 2026-10-20
**Status**: Aprovado
**Autor**: Devin AI

## 1. Contexto

O FlowFuel utiliza atualmente Overpass API (OpenStreetMap) para busca de postos de combustível, mas esta API tem apresentado problemas de disponibilidade e instabilidade desde abril de 2026 (retornando HTTP 406 Not Acceptable). O projeto já possui integração com Open Charge Map para eletropostos, que funciona corretamente.

Este design adiciona um novo endpoint usando Google Places API (New) como alternativa para busca de postos de combustível, mantendo o endpoint existente como fallback.

## 2. Objetivo

Implementar um novo endpoint REST `/api/v1/stations/nearby-google` que:

- Recebe latitude, longitude e raio de busca
- Consulta Google Places API (New) usando Nearby Search
- Filtra por tipo `gas_station`
- Retorna até 20 resultados ordenados por distância
- Usa o contrato existente `StationResponseDTO`
- Mantém compatibilidade com o aplicativo Android

## 3. Abordagem Escolhida

**Opção B**: Criar novo endpoint `/api/v1/stations/nearby-google` mantendo o endpoint Overpass como fallback.

**Justificativa**:
- Menor risco: endpoint atual continua funcionando
- Possibilidade de teste gradual no Android
- Rollback imediato via mudança de endpoint no cliente
- Rate limit e cache compartilhados (reutiliza infraestrutura existente)

## 4. Arquitetura

### 4.1 Novo Endpoint

**Endpoint**: `GET /api/v1/stations/nearby-google`

**Parâmetros**:
- `lat` (Double, obrigatório): Latitude, range -90 a 90
- `lng` (Double, obrigatório): Longitude, range -180 a 180
- `radius` (Integer, opcional, default 5000): Raio em metros, máximo 50000 (50km)

**Autenticação**: JWT (mesmo que endpoint atual)

**Rate Limiting**: Compartilhado com endpoint `/api/v1/stations/nearby` (10 req/min por usuário via bucket4j)

**Cache**: Redis com chave prefixada `google:stations:nearby:{lat}:{lng}:{radius}`, TTL 7 dias

### 4.2 Fluxo de Dados

```
Client → StationController.getNearbyStationsGoogle()
       → StationService.findNearbyGoogle()
       → checkRateLimit() (bucket4j compartilhado)
       → buildCacheKey() (prefixo "google:")
       → cacheService.get() (Redis)
       → Se não em cache:
           → GooglePlacesClient.searchNearby()
           → Mapear para StationResponseDTO
           → Calcular distância (Haversine)
           → Ordenar por distância
           → cacheService.put()
       → Retornar List<StationResponseDTO>
```

### 4.3 Componentes

**Novos arquivos**:
- `GooglePlacesClient.java` - Cliente HTTP para Google Places API
- `GooglePlacesRequestDTO.java` - Request body (POST)
- `GooglePlacesResponseDTO.java` - Response body
- `GooglePlacesKeyValidator.java` - Validador de API key no startup
- `StationController` - Adicionar método `getNearbyStationsGoogle()`
- `StationService` - Adicionar método `findNearbyGoogle()`

**Arquivos modificados**:
- `StationController.java` - Adicionar novo endpoint
- `StationService.java` - Adicionar método `findNearbyGoogle()`
- `application.properties` - Adicionar configurações Google Places
- `.env.example` - Documentar nova variável de ambiente

## 5. Integração Google Places API

### 5.1 Configuração

**Endpoint**: `POST https://places.googleapis.com/v1/places:searchNearby`

**Headers**:
- `X-Goog-Api-Key`: API key do Google Places
- `Content-Type: application/json`
- `X-Goog-FieldMask`: `places.id,places.displayName,places.formattedAddress,places.location`

**Request Body**:
```json
{
  "location": {
    "latitude": -23.5505,
    "longitude": -46.6333
  },
  "includedTypes": ["gas_station"],
  "maxResultCount": 20,
  "rankPreference": "DISTANCE"
}
```

### 5.2 Mapeamento de Campos

Google Places → `StationResponseDTO`:

| Google Places | StationResponseDTO | Observação |
|---------------|---------------------|------------|
| `places.id` | `placeId` | Prefixo `google:` |
| `places.displayName.text` | `name` | Nome do estabelecimento |
| `places.formattedAddress` | `street` | Endereço completo |
| `places.location.latitude` | `latitude` | Coordenada |
| `places.location.longitude` | `longitude` | Coordenada |
| - | `type` | `StationType.FUEL` |
| - | `rating` | `null` (não solicitado) |
| - | `houseNumber` | `null` (não parseado) |
| - | `distanceMeters` | Calculado via Haversine |

### 5.3 Configuração de Aplicação

**application.properties**:
```properties
# Google Places API (New) para busca de postos de combustível
flowfuel.station.google-places.base-url=${GOOGLE_PLACES_BASE_URL:https://places.googleapis.com}
flowfuel.station.google-places.api-key=${GOOGLE_PLACES_API_KEY:}
```

**.env.example**:
```properties
# Google Places API Key (New) - gere em console.cloud.google.com
# Necessária para o endpoint /api/v1/stations/nearby-google
GOOGLE_PLACES_API_KEY=<sua-key-do-google-places>
```

**Fly.io secrets**:
```bash
flyctl secrets set GOOGLE_PLACES_API_KEY="<sua-key>" -a flowfuel-api
```

## 6. Segurança

### 6.1 Proteção da API Key

- API key configurada via variável de ambiente `GOOGLE_PLACES_API_KEY`
- Nunca versionada no Git
- Nunca retornada ao cliente
- Nunca logada
- Header `X-Goog-Api-Key` nas requisições

### 6.2 Validação de Key

**GooglePlacesKeyValidator**:
- Validador que roda no startup da aplicação
- Em produção (`prod` profile): falha o boot se key ausente
- Em dev/test: key ausente não bloqueia (endpoint retorna erro em runtime)
- Mensagem clara: "GOOGLE_PLACES_API_KEY é obrigatória em produção"

### 6.3 Validação de Parâmetros

Bean Validation no Controller:
- Latitude: `@DecimalMin("-90") @DecimalMax("90")`
- Longitude: `@DecimalMin("-180") @DecimalMax("180")`
- Radius: `@Positive @Max(50000)`

## 7. Tratamento de Erros

### 7.1 Erros do Google Places

| Código HTTP | Tratamento | Response |
|-------------|------------|----------|
| 400 | Request inválido | `ExternalServiceUnavailableException` → 503 |
| 403 | Key inválida/sem permissão | `ExternalServiceUnavailableException` → 503 |
| 429 | Quota excedida | `RateLimitExceededException` → 429 |
| 5xx | Erro do serviço | `ExternalServiceUnavailableException` → 503 |
| Timeout | Falha de conexão | `ExternalServiceUnavailableException` → 503 |

### 7.2 Casos de Erro Específicos

- **Key ausente em produção**: Aplicação não sobe (fail-fast no startup)
- **Key ausente em dev**: Endpoint retorna 503 quando chamado
- **Resposta vazia**: Retorna lista vazia (não é erro)
- **Resposta incompleta**: Filtra campos nulos, retorna o que for válido

## 8. Custos e Quota

### 8.1 SKU e Preços (consultado em 20/10/2026)

**Fonte**: [Google Places API Pricing](https://developers.google.com/maps/billing/pricing)

- **Nearby Search (New) - Basic**: $0.032 por 1.000 requisições
- **FieldMask básico** (id, displayName, formattedAddress, location): sem custo adicional
- **Franquia gratuita**: $200/mês (varia por região)

### 8.2 Estimativa de Uso

Assumindo uso pessoal (1-2 usuários):
- 10.000 requisições/mês = $0.32
- Dentro da franquia gratuita

### 8.3 Regras de Cache e Armazenamento

- **Cache Redis**: Permitido (não é persistência permanente)
- **TTL**: 7 dias (reduz chamadas à API)
- **Persistência no PostgreSQL**: NÃO permitido (viola termos do Google Places)
- **Armazenamento de IDs**: Permitido para referência temporária

## 9. Testes

### 9.1 Testes Unitários

**GooglePlacesClientTest**:
- Mock do RestClient
- Testar parsing de resposta JSON
- Testar mapeamento para StationResponseDTO
- Testar tratamento de erros HTTP (400, 403, 429, 5xx)
- Testar timeout

### 9.2 Testes de Integração

**StationControllerIntegrationTest**:
- Testar endpoint completo com MockMvc
- Testar validação de parâmetros (lat/lng/radius inválidos)
- Testar rate limit (bucket4j)
- Testar cache (sem chamada real ao Google)
- Testar resposta vazia
- Testar resposta com múltiplos postos

### 9.3 Teste Manual

Script curl para validar com API key real:
```bash
TOKEN=$(curl -s -X POST http://localhost:8090/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"dev@flowfuel.local","password":"Dev@12345"}' \
  | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)

curl -s "http://localhost:8090/api/v1/stations/nearby-google?lat=-23.5505&lng=-46.6333&radius=1000" \
  -H "Authorization: Bearer $TOKEN"
```

Validar:
- Até 20 resultados
- Coordenadas válidas
- Distâncias calculadas corretamente
- Ordenação por proximidade

## 10. Plano de Rollback

Se o Google Places API apresentar problemas:

1. **Android**: Reverter para endpoint `/api/v1/stations/nearby` (Overpass + Open Charge Map)
2. **Backend**: Remover env var `GOOGLE_PLACES_API_KEY` ou configurar `GOOGLE_PLACES_ENABLED=false`
3. **Monitoramento**: Acompanhar logs de erro e quota no Google Cloud Console

O endpoint Overpass continua disponível como fallback.

## 11. Atualização de Documentação

Após implementação:

1. **docs/inventory.md**: Adicionar endpoint `/api/v1/stations/nearby-google`
2. **docs/spec/openapi.yaml**: Adicionar especificação OpenAPI do novo endpoint
3. **docs/endpoint-flows/postos.md**: Atualizar fluxo técnico para incluir Google Places
4. **README.md**: Documentar novo endpoint e instruções para obter API key
5. **docs/deploy.md**: Adicionar instrução para configurar `GOOGLE_PLACES_API_KEY` no Fly.io

## 12. Escopo (Fora do Escopo)

NÃO está incluído nesta implementação:

- Cálculo de rotas ou tempo de viagem
- Directions API ou Routes API
- SDK de mapas do Google
- Consulta de preços de combustível
- Consulta de fotos, avaliações ou comentários
- Substituição do endpoint Overpass existente
- Persistência de dados do Google Places no PostgreSQL
- Cache no banco de dados (apenas Redis)
