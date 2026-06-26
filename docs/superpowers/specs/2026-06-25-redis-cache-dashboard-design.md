# Cache Redis — Dashboard e Veículo Ativo

## Contexto

`GET /dashboard/vehicle/{vehicleId}` (`DashboardService.getVehicleDashboard`) faz várias queries agregadas sobre `refuels` (contagem, soma, médias, busca de full-tanks) a cada chamada — candidato natural a cache, pois os dados só mudam em escrita de `Refuel`/`Vehicle`. `GET /vehicles/active` é uma query simples (`findByUserId` + filtro em memória), mas o usuário pediu para cachear também, então entra no mesmo mecanismo por consistência.

O Redis já está provisionado para rate-limiting (dev via compose; produção ainda pendente — ver [spec de rate-limit](2026-06-25-provision-redis-rate-limit-design.md)). Esta feature usa o mesmo `REDIS_URL`, mas com keyspace e client próprios — não compartilha a conexão Lettuce manual do bucket4j.

## Objetivo

Cachear:
- `GET /dashboard/vehicle/{vehicleId}` → chave `dashboard::{vehicleId}`
- `GET /vehicles/active` → chave `active-vehicle::{userId}`

TTL de 5 minutos em ambas, como rede de segurança. Invalidação explícita (`@CacheEvict`) nos pontos de escrita que afetam cada cache, para que o usuário veja o dado atualizado imediatamente após uma escrita relevante (não esperando o TTL).

## Fora de escopo

- Cache de qualquer outro endpoint (refuels, vehicle-events, etc.).
- Cache de segundo nível do Hibernate.
- Métricas de cache hit/miss (pode ser adicionado depois via Micrometer, se necessário).

## Dependências e infraestrutura

Adicionar `spring-boot-starter-data-redis` ao `pom.xml` (reaproveita o `lettuce-core` já presente como transitiva, sem conflito de versão a verificar). Habilitar `@EnableCaching` na configuração principal.

Novo `CacheConfig`:
- `RedisCacheManager` configurado com TTL default de 5 minutos e serialização JSON (Jackson) para os values — os DTOs cacheados (`DashboardDTO`, `VehicleResponseDTO`) precisam ser serializáveis via Jackson (já são, por serem retornados como JSON nas respostas REST).
- `CacheErrorHandler` customizado: em `handleCacheGetError`/`handleCachePutError`/`handleCacheEvictError`, loga warning e **não relança a exceção** — mesma filosofia fail-open do `RateLimitFilter`. Se o Redis estiver indisponível, `@Cacheable` se comporta como cache miss (chama o método normalmente) e `@CacheEvict` simplesmente não evict (sem efeito, sem erro).
- Reusa a mesma property `flowfuel.rate-limit.redis-url` (ou introduz `spring.data.redis.url` apontando para o mesmo `REDIS_URL`) — usar a property padrão do Spring Boot (`spring.data.redis.url=${REDIS_URL:redis://localhost:6379}`) para que o `RedisConnectionFactory` autoconfigurado pelo Spring Boot funcione sem bean manual.

## Pontos de cache e invalidação

### `dashboard::{vehicleId}`

- `@Cacheable(cacheNames = "dashboard", key = "#vehicleId")` em `DashboardService.getVehicleDashboard` — cuidado: a autorização (`ensureOwnsVehicle`) deve continuar acontecendo **antes** do cache hit (ou seja, a verificação de ownership não pode ser pulada por causa do cache). Como o cache é só por `vehicleId` (não por `userId`), é necessário manter a checagem de ownership fora da região cacheada, ou incluir a checagem antes do `@Cacheable` interceptar a chamada — Spring AOP intercepta o método inteiro, então a ordem dentro do método não importa para segurança: **a chamada ao método cacheado só ocorre após a autorização ser resolvida pelo Spring**, então a estrutura precisa separar "verificar ownership" (sempre executa) de "buildDashboard" (cacheável). Refatoração necessária: extrair `buildDashboard(Vehicle vehicle)` para um método `@Cacheable` próprio chamado depois do `ensureOwnsVehicle`, mantendo a query/autorização do veículo sempre fora do cache.
- Evict (`@CacheEvict(cacheNames = "dashboard", key = "#vehicleId ou #refuel.vehicle.id")`) em:
  - `RefuelService.createRefuel`, `updateRefuel`, `deleteRefuel`
  - `VehicleService.updateVehicle` (pode mudar `energyType`, que muda a estrutura do dashboard)
  - `VehicleService.deleteVehicle`

### `active-vehicle::{userId}`

- `@Cacheable(cacheNames = "active-vehicle", key = "#user.id")` em `VehicleService.getActiveVehicle`.
- Evict em:
  - `VehicleService.setActiveVehicle` (troca qual veículo é o ativo)
  - `VehicleService.deleteVehicle` (se o veículo excluído for o ativo, o cache ficaria apontando para um veículo que não existe mais)

`VehicleService.createVehicle` não precisa evict — um veículo novo não nasce ativo (`isActive` não é setado na criação), então não invalida o veículo ativo atual.

## Plano de testes

- Teste de unidade: 2ª chamada a `getVehicleDashboard`/`getActiveVehicle` com os mesmos parâmetros não bate no repositório (usa `Mockito.verify(..., times(1))` após duas chamadas).
- Teste de integração (Testcontainers Redis, mesmo padrão do `RateLimitFilterIntegrationTest`): criar refuel → evict → próxima chamada ao dashboard reflete o novo dado.
- Teste de fail-open: simular Redis indisponível (porta errada) e confirmar que `getVehicleDashboard`/`getActiveVehicle` ainda retornam 200 com o dado correto (sem cache, calculado direto do Postgres).
- Teste de autorização: usuário sem ownership do veículo continua recebendo 403 mesmo com cache populado por outro usuário (o cache é por `vehicleId`, não por `userId` — então o teste deve confirmar que o cache não é usado como bypass de autorização).

## Riscos / Pontos de atenção

- Separar a checagem de ownership do método cacheado em `DashboardService` é uma refatoração pequena, mas obrigatória — sem ela, a chave de cache por `vehicleId` (não por `userId`) é inofensiva porque o `ensureOwnsVehicle` roda sempre antes (fora do método cacheado), mas é importante revisar isso no code review para não introduzir um bypass de autorização acidental.
- Se o Redis de produção (item de rate-limit) nunca for provisionado, o cache também roda sempre em fail-open (cache miss permanente) — funciona, mas não traz o ganho de performance esperado. Mesma dependência de infraestrutura das duas features.
