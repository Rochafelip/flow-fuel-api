# FlowFuel API — Documentação

> Documentação gerada via engenharia reversa do código-fonte (`com.devappmobile.flowfuel`), seguindo o processo spec-driven descrito em [CLAUDE.md](../CLAUDE.md). Regras de negócio não explícitas no código estão marcadas `[INFERIDO]`.

## Visão Geral

FlowFuel é uma API REST (Spring Boot) para controle de abastecimentos e custos de veículos (combustão, elétrico e híbrido). Um usuário autenticado cadastra seus veículos, registra abastecimentos e eventos (manutenção, seguro, taxas, etc.), consulta um dashboard com estatísticas agregadas de consumo e gasto por veículo e exporta esse histórico em CSV, XLSX ou PDF. Também pode emprestar um veículo por prazo definido a outro usuário (com convite via push) e buscar postos e eletropostos próximos. Autenticação é via JWT (access + refresh token opaco), com fluxo de ativação de conta por e-mail e reset de senha.

## Arquitetura de Alto Nível

```mermaid
flowchart TB
    Client[Cliente / App] -->|HTTPS| API[FlowFuel API - Spring Boot]
    API --> JwtFilter[JwtAuthenticationFilter]
    API --> RateLimit[RateLimitFilter - bucket4j]
    RateLimit --> Redis[(Redis)]
    API --> DB[(PostgreSQL)]
    API --> R2[(Cloudflare R2 - fotos de perfil e de veículo)]
    API --> SMTP[SMTP - e-mails de ativação/reset]
    API --> Sentry[Sentry - monitoramento de erros]
    API --> FCM[Firebase Cloud Messaging - push]
    API --> Overpass[Overpass API - postos]
    API --> OCM[Open Charge Map - eletropostos]
    API --> Nominatim[Nominatim - geocodificação]
    API -->|cache de postos| Redis
    API --> Jobs[Jobs agendados: cleanup de tokens]
    Jobs --> DB
```

## Índice

### Inventário
- [Fase 1 — Inventário de Endpoints, Middlewares, Entidades e Integrações](inventory.md)

### Especificação Técnica
- [OpenAPI 3.0 (openapi.yaml)](spec/openapi.yaml)

### Fluxos de Negócio
- [Cadastro e Ativação de Usuário](business-flows/cadastro-e-ativacao-de-usuario.md)
- [Autenticação (Login / Refresh / Logout)](business-flows/autenticacao.md)
- [Reset de Senha](business-flows/reset-de-senha.md)
- [Perfil de Usuário](business-flows/perfil-de-usuario.md)
- [Gestão de Veículos](business-flows/gestao-de-veiculos.md)
- [Abastecimentos (Refuels)](business-flows/abastecimentos.md)
- [Eventos de Veículo](business-flows/eventos-de-veiculo.md)
- [Dashboard de Estatísticas do Veículo](business-flows/dashboard.md)
- [Compartilhamento de Veículo](business-flows/compartilhamento-de-veiculo.md)
- [Postos e Eletropostos Próximos](business-flows/postos-proximos.md)
- [Exportação de Relatórios](business-flows/exportacao-de-relatorios.md)
- [Notificações Push](business-flows/notificacoes-push.md)

### Fluxos de Endpoint (Técnico)
- [Autenticação e Usuário](endpoint-flows/autenticacao-e-usuario.md)
- [Veículos](endpoint-flows/veiculos.md)
- [Abastecimentos](endpoint-flows/abastecimentos.md)
- [Eventos de Veículo](endpoint-flows/eventos-de-veiculo.md)
- [Dashboard](endpoint-flows/dashboard.md)
- [Auditoria](endpoint-flows/auditoria.md)
- [Compartilhamento de Veículo](endpoint-flows/compartilhamento-de-veiculo.md)
- [Postos](endpoint-flows/postos.md)
- [Exportação](endpoint-flows/exportacao.md)
- [Dispositivos (Push)](endpoint-flows/dispositivos.md)

### Roadmap de Melhorias
- [Visão geral do roadmap (bugs e débitos técnicos encontrados durante a documentação)](roadmap/README.md)

## Convenção de Paginação

Endpoints de listagem paginados — hoje `GET /refuels/vehicle/{vehicleId}` e `GET /vehicle-events/vehicle/{vehicleId}` — seguem a convenção padrão do Spring Data (`Pageable`/`Page<T>`):

- Query params: `?page=0&size=20` (`page` 0-indexed; `size` default 20 via `@PageableDefault`). Spring também aceita `&sort=campo,asc|desc` por convenção do framework, ainda que não documentado explicitamente em cada endpoint.
- Resposta envolvida em `PageResponseDTO<T>` (`common/PageResponseDTO.java`): `content` (lista da página atual), `page`, `size`, `totalElements`, `totalPages`.
- `GET /vehicles` também segue essa convenção (`PageableDefault(size = 20, sort = "createdAt", direction = DESC)`). `GET /vehicles/active` retorna um único veículo (não é uma listagem) e não é paginado.

## Glossário

| Termo | Significado |
|---|---|
| **Veículo ativo** | Veículo marcado como "atual" pelo usuário; usado como padrão em telas/consultas quando nenhum `vehicleId` é especificado |
| **Abastecimento (Refuel)** | Registro de reabastecimento (combustível ou energia elétrica) com quantidade, preço unitário e total calculado |
| **Evento de veículo** | Registro de manutenção, seguro, troca de óleo, lavagem, pneus, taxas, documentos ou outro custo associado ao veículo |
| **Token opaco** | Token aleatório entregue ao cliente; apenas seu hash SHA-256 é persistido no banco (usado em refresh, ativação e reset de senha) |
| **PENDING_ACTIVATION / ACTIVE** | Estados do `UserStatus`; usuário só pode logar após ativar a conta via link enviado por e-mail |
| **EnergyType** | Tipo de energia do veículo: `COMBUSTION`, `ELECTRIC`, `HYBRID` |
| **Compartilhamento (Vehicle Share)** | Empréstimo temporário de um veículo do **dono** a um **convidado** já cadastrado. Ciclo: `PENDING` → `ACTIVE` (prazo começa no aceite) / `REJECTED` / `REVOKED` |
| **Acesso de convidado** | O que o convidado pode fazer num veículo emprestado: atualizar odômetro e registrar eventos `FUEL`, `CAR_WASH`, `TIRES`, `OTHER` |
| **Device token** | Token FCM de um aparelho; vincula o aparelho ao usuário para receber push |
| **Posto / Eletroposto** | `StationType.FUEL` (Overpass/OSM) e `StationType.ELECTRIC` (Open Charge Map); não são persistidos, só cacheados |
| **Fail-open** | Se o Redis estiver fora, cache e rate limit são ignorados e a requisição segue normalmente |
| **`[INFERIDO]`** | Marcação para regras de negócio deduzidas do comportamento do código, não explícitas (ex: validação ausente, comentário, doc oficial) — requer confirmação com o time |

## Pontos de Atenção Consolidados

> Itens descobertos durante a documentação que merecem atenção do time (ver detalhes nos documentos de origem):

- Inconsistência entre os paths reais dos controllers (sem prefixo `/api/v1`) e as strings de whitelist do `JwtAuthenticationFilter`/`SecurityConfig` (com prefixo `/api/v1/auth/...`) — ver [inventory.md](inventory.md#pontos-de-atenção).
- Inconsistência de status code em operações de delete entre `UserController` (204) e os demais controllers (200 com corpo vazio).
- `VehicleEventType.FUEL` possivelmente redundante com `RefuelType.FUEL`.
- Itens de roadmap já identificados e endereçados (bugs e melhorias técnicas) estão detalhados em [roadmap/README.md](roadmap/README.md).
- Compartilhamentos vencidos nunca mudam para `EXPIRED` e bloqueiam novos convites para o mesmo veículo (409) até o dono revogar — ver [business-flows/compartilhamento-de-veiculo.md](business-flows/compartilhamento-de-veiculo.md#pontos-de-atenção).
- `POST /vehicle-shares` com e-mail inexistente devolve 404 com o e-mail no `detail` (enumeração de contas).
- `/stations/nearby` cacheia resultado parcial (um provedor fora) por 10 min e aceita `radius` sem limite superior — ver [endpoint-flows/postos.md](endpoint-flows/postos.md#pontos-de-atenção).
- Status de enum/data inválidos em query param: os docs de abastecimentos/eventos dizem 500, mas `ResponseEntityExceptionHandler` (Spring 6) responde 400 a `TypeMismatchException`. Confirmar com teste e corrigir o doc errado.
- `veiculos.md` e `eventos-de-veiculo.md` (Fases 3/4) ainda descrevem `PUT /vehicles/{id}/odometer` e `POST /vehicle-events` como "somente dono"; hoje aceitam convidado com share ativo.
- `inventory.md` §4 ainda cita AWS S3 / Backblaze B2; o código usa Cloudflare R2 (`storage/R2StorageService.java`).
