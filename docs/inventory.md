# FlowFuel API — Fase 1: Descoberta e Inventário

> Gerado via engenharia reversa do código-fonte (`com.devappmobile.flowfuel`). Regras de negócio não explícitas no código estão marcadas `[INFERIDO]`.

## 1. Inventário de Endpoints

| Método | Path | Controller | Auth? | Descrição curta |
|---|---|---|---|---|
| GET | `/` | HomeController | Não | Health check |
| POST | `/api/v1/auth/register` | UserController | Não | Cadastro de usuário (status PENDING_ACTIVATION) |
| POST | `/api/v1/auth/activate` | UserController | Não | Ativa conta via token |
| POST | `/api/v1/auth/resend-activation` | UserController | Não | Reenvia e-mail de ativação |
| POST | `/api/v1/auth/login` | UserController | Não | Login; retorna access+refresh token |
| POST | `/api/v1/auth/refresh` | UserController | Não | Rotaciona refresh token |
| POST | `/api/v1/auth/logout` | UserController | Sim | Revoga refresh token |
| POST | `/api/v1/auth/forgot-password` | UserController | Não | Inicia reset de senha |
| POST | `/api/v1/auth/reset-password` | UserController | Não | Conclui reset de senha |
| PUT | `/api/v1/auth/{userId}/password` | UserController | Sim (dono) | Altera senha |
| POST | `/api/v1/auth/{userId}/upload-profile-picture` | UserController | Sim (dono) | Upload de foto de perfil (S3) |
| GET | `/api/v1/auth/{userId}/profile-picture` | UserController | Sim (dono) | Download da foto de perfil |
| DELETE | `/api/v1/auth/{userId}/profile-picture` | UserController | Sim (dono) | Remove foto de perfil |
| GET | `/api/v1/auth/{userId}/profile` | UserController | Sim (dono) | Consulta perfil |
| PUT | `/api/v1/auth/{userId}/profile` | UserController | Sim (dono) | Atualiza perfil |
| DELETE | `/api/v1/auth/{userId}` | UserController | Sim (dono) | Exclui usuário |
| POST | `/api/v1/vehicles` | VehicleController | Sim | Cria veículo |
| GET | `/api/v1/vehicles` | VehicleController | Sim | Lista veículos do usuário (paginado) |
| GET | `/api/v1/vehicles/active` | VehicleController | Sim | Veículo ativo atual |
| GET | `/api/v1/vehicles/{id}` | VehicleController | Sim (dono) | Detalhe do veículo |
| PUT | `/api/v1/vehicles/{id}` | VehicleController | Sim (dono) | Atualiza veículo |
| PUT | `/api/v1/vehicles/{id}/odometer` | VehicleController | Sim (dono) | Atualiza odômetro |
| PUT | `/api/v1/vehicles/{id}/active` | VehicleController | Sim (dono) | Define veículo ativo |
| DELETE | `/api/v1/vehicles/{id}` | VehicleController | Sim (dono) | Exclui veículo (cascade) |
| POST | `/api/v1/vehicles/{id}/photo` | VehicleController | Sim (dono) | Upload de foto do veículo (R2) |
| GET | `/api/v1/vehicles/{id}/photo` | VehicleController | Sim (dono) | Download da foto do veículo |
| DELETE | `/api/v1/vehicles/{id}/photo` | VehicleController | Sim (dono) | Remove foto do veículo |
| POST | `/api/v1/refuels` | RefuelController | Sim | Cria abastecimento |
| GET | `/api/v1/refuels/vehicle/{vehicleId}` | RefuelController | Sim (dono veículo) | Lista abastecimentos (filtro data) |
| GET | `/api/v1/refuels/{id}` | RefuelController | Sim (dono) | Detalhe do abastecimento |
| PUT | `/api/v1/refuels/{id}` | RefuelController | Sim (dono) | Atualiza abastecimento |
| DELETE | `/api/v1/refuels/{id}` | RefuelController | Sim (dono) | Exclui abastecimento |
| POST | `/api/v1/vehicle-events` | VehicleEventController | Sim | Cria evento (manutenção, seguro, etc) |
| GET | `/api/v1/vehicle-events/vehicle/{vehicleId}` | VehicleEventController | Sim (dono veículo) | Lista eventos (filtro tipo/data) |
| GET | `/api/v1/vehicle-events/{id}` | VehicleEventController | Sim (dono) | Detalhe do evento |
| PUT | `/api/v1/vehicle-events/{id}` | VehicleEventController | Sim (dono) | Atualiza evento |
| DELETE | `/api/v1/vehicle-events/{id}` | VehicleEventController | Sim (dono) | Exclui evento |
| GET | `/api/v1/dashboard/vehicle/{vehicleId}` | DashboardController | Sim (dono veículo) | Estatísticas do veículo |
| GET | `/api/v1/audit-logs` | AuditLogController | Sim (admin) | Logs de auditoria paginados (filtro userId/action) |
| POST | `/api/v1/vehicle-shares` | VehicleShareController | Sim (dono veículo) | Convida outro usuário a usar o veículo (PENDING) |
| POST | `/api/v1/vehicle-shares/{id}/accept` | VehicleShareController | Sim (convidado) | Aceita convite (ACTIVE por `durationDays`) |
| POST | `/api/v1/vehicle-shares/{id}/reject` | VehicleShareController | Sim (convidado) | Recusa convite |
| DELETE | `/api/v1/vehicle-shares/{id}` | VehicleShareController | Sim (dono) | Revoga compartilhamento |
| GET | `/api/v1/vehicle-shares/vehicle/{vehicleId}` | VehicleShareController | Sim (dono veículo) | Compartilhamento corrente do veículo (200 ou 204) |
| GET | `/api/v1/vehicle-shares/pending` | VehicleShareController | Sim | Convites pendentes recebidos |
| GET | `/api/v1/vehicle-shares/active-for-me` | VehicleShareController | Sim | Veículos emprestados ao usuário |
| GET | `/api/v1/stations/nearby` | StationController | Sim | Postos e eletropostos próximos (Overpass + Open Charge Map) |
| GET | `/api/v1/stations/geocode` | StationController | Sim | Bairro/cidade → coordenadas (Nominatim) |
| GET | `/api/v1/exports/refuels` | ExportController | Sim (dono veículo) | Exporta abastecimentos (CSV/XLSX/PDF) |
| GET | `/api/v1/exports/events` | ExportController | Sim (dono veículo) | Exporta eventos (CSV/XLSX/PDF) |
| POST | `/api/v1/devices` | DeviceTokenController | Sim | Registra/reatribui token FCM do dispositivo |
| DELETE | `/api/v1/devices/{token}` | DeviceTokenController | Sim (dono token) | Remove token FCM (idempotente, 204) |

## 2. Middlewares Globais

- **JwtAuthenticationFilter** (`config/JwtAuthenticationFilter.java`) — valida `Authorization: Bearer`, popula SecurityContext com `ROLE_USER`. Rotas isentas: endpoints públicos de `/auth` (register, login, refresh, forgot/reset-password, activate, resend-activation), `/actuator/health/**`, Swagger/OpenAPI, `/`.
- **RateLimitFilter** (`config/RateLimitFilter.java`, `config/RateLimitingConfig.java`) — Redis + bucket4j, limite por IP:
  - login: 5/min
  - register: 10/h
  - forgot-password: 3/h
  - resend-activation: 3/h
  - Fail-open se Redis indisponível (loga warning).
- **Rate limit por usuário em `/stations/*`** (`station/StationService.java:checkRateLimit`) — não é filtro HTTP, roda no service: 10 req/min por `userId`, bucket único para `/nearby` e `/geocode`. `/geocode` tem ainda um limite **global** de 1 req/s (bucket `nominatim-global-rate-limit`), exigido pela política de uso do Nominatim. Ambos fail-open sem Redis.
- **CORS** (`CorsProperties`) — origens default: `http://localhost:8081`, `http://192.168.1.2:8081`, `http://localhost:5173`.
- **GlobalExceptionHandler** (`config/GlobalExceptionHandler.java`) — `@RestControllerAdvice`, padroniza erros em RFC 7807 `ProblemDetail` (status, code, requestId, errors[]).
- **SecurityConfig** (`config/SecurityConfig.java`) — sessão STATELESS, CSRF desabilitado, headers de segurança (HSTS em prod), entry point customizado.

## 3. Entidades e Relacionamentos

| Entidade | Tabela | Relacionamentos |
|---|---|---|
| **User** | `users` | 1—N `Vehicle` |
| **Vehicle** | `vehicles` | N—1 `User`; 1—N `Refuel`; 1—N `VehicleEvent` |
| **Refuel** | `refuels` | N—1 `Vehicle`; `totalAmount` calculado via `@PrePersist/@PreUpdate` (energyAmount × pricePerUnit) |
| **VehicleEvent** | `vehicle_events` | N—1 `Vehicle`; índices por (vehicle_id, event_date) e (vehicle_id, type) |
| **RefreshToken** | `refresh_tokens` | N—1 `User`; auto-referência `replacedBy` (rotação de token) |
| **ActivationToken** | `activation_tokens` | N—1 `User` |
| **PasswordResetToken** | `password_reset_tokens` | N—1 `User` |
| **VehicleShare** | `vehicle_shares` (V11) | N—1 `Vehicle`; N—1 `User` (owner); N—1 `User` (guest); `ON DELETE CASCADE` nos três; índice único parcial: 1 share PENDING/ACTIVE por veículo |
| **DeviceToken** | `device_tokens` (V10) | PK = token FCM (string); N—1 `User` (`ON DELETE CASCADE`) |

**Enums:** `UserStatus` (PENDING_ACTIVATION, ACTIVE), `EnergyType` (COMBUSTION, ELECTRIC, HYBRID), `RefuelType` (FUEL, ELECTRIC), `VehicleEventType` (FUEL, MAINTENANCE, OIL_CHANGE, CAR_WASH, TIRES, INSURANCE, TAX, DOCUMENTS, OTHER), `VehicleShareStatus` (PENDING, ACTIVE, REJECTED, REVOKED, EXPIRED), `DevicePlatform` (ANDROID), `StationType` (FUEL, ELECTRIC), `ExportFormat` (CSV, XLSX, PDF), `ErrorCode`.

Postos (`StationResponseDTO`) e resultados de geocodificação não são persistidos no banco: vêm dos provedores externos e ficam só no cache Redis.

Todos os tokens temporários (refresh, activation, password reset) seguem o padrão opaco: token bruto entregue ao cliente, hash SHA-256 persistido, expiração configurável por env var.

## 4. Integrações Externas

| Integração | Uso | Configuração |
|---|---|---|
| **AWS S3 / Backblaze B2** (`storage/S3StorageService.java`) | Upload/download/delete de foto de perfil; URLs presignadas (15 min) | `B2_S3_ENDPOINT`, `B2_S3_REGION`, `B2_S3_ACCESS_KEY`, `B2_S3_SECRET`, `B2_BUCKET_NAME` |
| **SMTP** (`user/SmtpAccountActivationNotifier.java`) | E-mails de ativação de conta e reset de senha | `MAIL_ENABLED` (default false), `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM` |
| **Redis** (`config/RateLimitingConfig.java`) | Backend de rate limiting (bucket4j) | `REDIS_URL` (default `redis://localhost:6379`) |
| **Sentry** (`config/SentryConfig.java`) | Monitoramento de erros/crashes | `sentry.dsn` |
| **Firebase Cloud Messaging** (`push/FirebasePushNotificationService.java`) | Push para os dispositivos do usuário (hoje só o convite de compartilhamento). Sem `PUSH_ENABLED=true` usa o stub `LoggingPushNotificationService` (só loga) | `PUSH_ENABLED` (default false), `PUSH_CREDENTIALS_FILE` ou `PUSH_CREDENTIALS_JSON` |
| **Overpass API** (OSM) (`station/client/OverpassClient.java`) | Postos de combustível (`amenity=fuel`) num raio; pública, sem key | `OVERPASS_BASE_URL` |
| **Open Charge Map** (`station/client/OpenChargeMapClient.java`) | Estações de recarga elétrica (máx. 20). Sem key, toda chamada falha (403) e só aparecem postos FUEL | `OPEN_CHARGE_MAP_BASE_URL`, `OPEN_CHARGE_MAP_API_KEY` |
| **Nominatim** (OSM) (`station/client/NominatimClient.java`) | Geocodificação de texto livre, restrita ao Brasil (`countrycodes=br`, `limit=5`) | `NOMINATIM_BASE_URL` |
| **Redis (cache de postos)** (`station/StationCacheService.java`, `station/GeocodeCacheService.java`) | Cache raw via a conexão Lettuce do rate limit: `nearby` TTL 10 min, `geocode` TTL 7 dias; fail-open | mesma `REDIS_URL` |

## 5. Jobs Agendados

| Job | Cron | Retenção | Finalidade |
|---|---|---|---|
| `RefreshTokenCleanupJob` | `0 0 3 * * *` (03:00) | 30 dias | Remove refresh tokens expirados |
| `ActivationTokenCleanupJob` | `0 45 3 * * *` (03:45) | 7 dias | Remove tokens de ativação expirados/usados |
| `PasswordResetTokenCleanupJob` | `0 30 3 * * *` (03:30) | 7 dias | Remove tokens de reset de senha expirados/usados |

## Pontos de Atenção

- `VehicleEventType.FUEL` parece redundante com `RefuelType.FUEL` — possível duplicidade de modelagem. `[INFERIDO — confirmar com time]`
- Comportamento de exclusão de usuário (soft vs hard delete) não está explícito no código. `[INFERIDO]`
- Não há papéis admin/moderador implementados, apenas `ROLE_USER`. `[INFERIDO]`
- **Inconsistência de paths `/api/v1`** (descoberta na Fase 2): os controllers reais NÃO usam o prefixo `/api/v1` (ex: `UserController` é `@RequestMapping("/auth")`, roteando `/auth/login`, não `/api/v1/auth/login` como listado na tabela acima). Porém `JwtAuthenticationFilter.shouldNotFilter` e `SecurityConfig` usam strings de whitelist com prefixo `/api/v1/auth/...`. Os endpoints públicos de auth, como roteados de fato, não coincidem com as strings de bypass do filtro JWT. `[INFERIDO — confirmar com time: qual dos dois é o comportamento pretendido; a tabela de endpoints acima precisa de correção dos paths]`
- **Inconsistência de status code em deletes**: `UserController` usa 204 No Content para operações de exclusão (logout, reset-password, change-password, delete-profile-picture), enquanto `VehicleController`, `RefuelController` e `VehicleEventController` retornam `void` sem `ResponseEntity`, resultando em 200 com corpo vazio para delete/set-active. `[descoberto na Fase 2]`
- Spring's multipart size limits podem rejeitar uploads antes da camada de serviço (`MultipartException`), sem handler explícito em `GlobalExceptionHandler` — comportamento real (413 vs 500) não confirmado. `[INFERIDO — confirmar com time]`
- **Compartilhamento sem expiração ativa** (descoberto ao documentar `vehicleshare`): nenhum job ou código atribui `VehicleShareStatus.EXPIRED`. Um share ACTIVE vencido continua ACTIVE no banco, o que bloqueia um novo convite para o mesmo veículo (409) até o dono revogar. Ver [business-flows/compartilhamento-de-veiculo.md](business-flows/compartilhamento-de-veiculo.md#pontos-de-atenção).
- **Seção 4 desatualizada**: a linha "AWS S3 / Backblaze B2" não reflete o código atual, que usa Cloudflare R2 (`storage/R2StorageService.java`). Não foi revisada nesta passada. `[confirmar e atualizar]`
