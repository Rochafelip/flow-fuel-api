# FlowFuel — Documentação Técnica Completa

> Última atualização: 2026-05-23
> Versão da aplicação: 0.3.3-SNAPSHOT
> Alinhada aos ADRs em [adr/INDEX.md](adr/INDEX.md) (revisão 2026-05-23)
> Estratégia de sincronização mobile: **Online-First com Cache Agressivo** ([ADR-012](adr/ADR-012-sincronizacao-online-first.md))
> Storage de foto de perfil: **Backblaze B2 (S3-compatível) via `StorageService`** ([ADR-005](adr/ADR-005-storage-cloud.md))

---

## Sumário

1. [Resumo do Projeto e Objetivo](#1-resumo-do-projeto-e-objetivo)
2. [Tecnologias Utilizadas](#2-tecnologias-utilizadas)
3. [Arquitetura do Projeto](#3-arquitetura-do-projeto)
4. [Padrões Arquiteturais](#4-padrões-arquiteturais)
5. [Autenticação e Segurança](#5-autenticação-e-segurança)
6. [Entidades e Relacionamentos](#6-entidades-e-relacionamentos)
7. [Regras de Negócio](#7-regras-de-negócio)
8. [Documentação Completa da API](#8-documentação-completa-da-api)
9. [Fluxo Principal da Aplicação](#9-fluxo-principal-da-aplicação)
10. [Banco de Dados](#10-banco-de-dados)
11. [Observabilidade e Tratamento de Erros](#11-observabilidade-e-tratamento-de-erros)
12. [Infraestrutura, Deploy e CI/CD](#12-infraestrutura-deploy-e-cicd)
13. [Sincronização Mobile (Online-First)](#13-sincronização-mobile-online-first)
14. [Como Rodar o Projeto](#14-como-rodar-o-projeto)
15. [Qualidade do Código e Melhorias Pendentes](#15-qualidade-do-código-e-melhorias-pendentes)
16. [Mapa de ADRs](#16-mapa-de-adrs)

---

## 1. Resumo do Projeto e Objetivo

**FlowFuel** é uma API REST para gestão de consumo de combustível e energia de veículos. O sistema permite cadastrar veículos, registrar abastecimentos (combustão, elétrico ou híbrido) e acompanhar métricas analíticas por veículo.

### Objetivo principal

Fornecer uma plataforma backend mobile-first (cliente Android nativo em Kotlin + Compose) — que centraliza o histórico de abastecimentos por usuário, calculando automaticamente:

- Consumo médio (km/L ou km/kWh)
- Total gasto em combustível/energia
- Preço médio por unidade
- Odômetro atualizado a cada abastecimento

### Público-alvo

Motoristas que querem monitorar custo e eficiência energética de seus veículos (combustão, elétricos ou híbridos).

### Estratégia geral

- **Monolito modular** (um artefato, módulos por feature)
- **Scale when it hurts**: sem Redis/Kafka/K8s até existir gargalo medido
- **Online-First** no cliente: app assume rede, degrada de forma controlada quando offline ([ADR-012](adr/ADR-012-sincronizacao-online-first.md))

---

## 2. Tecnologias Utilizadas

| Categoria | Tecnologia | Versão / Observação |
|---|---|---|
| Linguagem | Java | 21 |
| Framework | Spring Boot | 3.5.7 |
| Segurança | Spring Security + JWT (jjwt) | 0.11.5 — Access + Refresh ([ADR-003](adr/ADR-003-autenticacao-jwt.md)) |
| ORM / Persistência | Spring Data JPA + Hibernate | via Spring Boot |
| Banco (produção) | PostgreSQL Gerenciado | Railway / Neon ([ADR-004](adr/ADR-004-banco-postgresql.md)) |
| Banco (testes / dev) | PostgreSQL (Testcontainers) e/ou H2 | Testcontainers preferencial |
| Migrations | Flyway | V1, V2, V3 em `src/main/resources/db/migration` |
| Documentação | SpringDoc OpenAPI (Swagger UI) | 2.0.4 |
| Boilerplate | Lombok | via Spring Boot |
| Validação | Spring Validation (Bean Validation) | via Spring Boot |
| Build | Maven Wrapper (`mvnw`) | 3.x |
| Servidor HTTP | Tomcat embarcado | via Spring Boot |
| Observabilidade | Logback JSON (logstash-encoder 7.4) + Sentry SDK 7.18.1 | [ADR-008](adr/ADR-008-observabilidade-logs-sentry.md) |
| Padronização de erros | RFC 7807 `ProblemDetail` + `X-Request-Id` | [ADR-008](adr/ADR-008-observabilidade-logs-sentry.md) |
| Storage de objetos | AWS SDK v2 `s3` 2.20.110 + AWS SDK v1 `aws-java-sdk-s3` 1.12.548 (presigned URL) — endpoint S3-compatível (Backblaze B2) | [ADR-005](adr/ADR-005-storage-cloud.md) |
| Cache | (planejado) Caffeine — adiado por [ADR-013](adr/ADR-013-escalabilidade-scale-when-it-hurts.md) | NÃO implementado |
| Hospedagem | PaaS (Railway) | [ADR-006](adr/ADR-006-infraestrutura-paas.md) |
| CI/CD | GitHub Actions (mínimo) | [ADR-007](adr/ADR-007-ci-cd-minimalista.md) — pendente |

### Prefixo de API

Todos os controllers REST são publicados sob o prefixo `/api/v1` aplicado globalmente via `WebMvcConfig#configurePathMatch` ([ADR-002](adr/ADR-002-rest-versionamento.md)). Quebras incompatíveis devem migrar para `/api/v2` mantendo `/api/v1` por pelo menos 6 meses.

### Origens CORS permitidas

| Origem | Uso |
|---|---|
| `http://localhost:8081` | App Android (emulador / dev) |
| `http://192.168.1.2:8081` | App em rede local |
| `http://localhost:5173` | Frontend Vite/React (web — uso interno) |

`allowCredentials: true`, sem `*` globalmente.

---

## 3. Arquitetura do Projeto

Monolito modular ([ADR-001](adr/ADR-001-monolito-modular.md)) organizado por **feature**, não por camada técnica.

### Estrutura de pacotes

```
com.devappmobile.flowfuel/
├── FlowFuelApplication.java          ← Entry point Spring Boot
├── HomeController.java               ← Health check / página inicial
│
├── config/                           ← Configurações globais
│   ├── JwtUtil.java                  ← Geração e validação de access tokens
│   ├── JwtAuthenticationFilter.java  ← Filtro de autenticação (OncePerRequestFilter)
│   ├── SecurityConfig.java           ← Regras HTTP + CORS + BCrypt
│   ├── SwaggerConfig.java            ← Configuração OpenAPI / Swagger UI
│   └── WebMvcConfig.java             ← Prefixo global /api/v1
│
├── common/                           ← Infra compartilhada
│   ├── PageResponseDTO.java          ← Envelope de paginação
│   └── error/
│       ├── AppException.java         ← Exceção de domínio com ErrorCode
│       ├── ErrorCode.java            ← Catálogo de códigos de erro
│       ├── ProblemDetailWriter.java  ← Serializador RFC 7807
│       └── RequestIdFilter.java      ← MDC requestId + header X-Request-Id
│
├── exception/                        ← Exceções específicas de domínio
│   ├── BusinessRuleException.java
│   ├── ConflictException.java
│   ├── ForbiddenOperationException.java
│   └── ResourceNotFoundException.java
│
├── storage/                          ← Abstração de object storage (ADR-005)
│   ├── StorageService.java           ← Interface upload/delete/getUrl/download
│   └── S3StorageService.java         ← Implementação S3-compatível (Backblaze B2)
│
├── user/                             ← Módulo de usuários + autenticação
│   ├── User.java
│   ├── UserRepository.java
│   ├── UserService.java
│   ├── UserController.java           ← /api/v1/auth/*
│   ├── UserRegisterDTO.java
│   ├── UserResponseDTO.java          ← NÃO expõe senha
│   ├── ChangePasswordRequest.java
│   ├── RefreshRequest.java
│   ├── RefreshToken.java             ← Entidade JPA (refresh_tokens)
│   ├── RefreshTokenRepository.java
│   ├── RefreshTokenService.java      ← Emissão / rotação / revogação
│   ├── RefreshTokenCleanupJob.java   ← Limpeza diária de tokens expirados
│   ├── UploadResponse.java           ← { internalUrl, signedUrl } para upload de foto
│   └── TokenPairResponse.java        ← { accessToken, refreshToken, expiresIn }
│
├── vehicle/                          ← Módulo de veículos
│   ├── Vehicle.java
│   ├── EnergyType.java               ← Enum: COMBUSTION, ELECTRIC, HYBRID (persistido como STRING)
│   ├── VehicleRepository.java
│   ├── VehicleService.java
│   ├── VehicleController.java        ← /api/v1/vehicles/*
│   └── dto/
│       ├── VehicleRequestDTO.java
│       └── VehicleResponseDTO.java
│
├── refuel/                           ← Módulo de abastecimentos
│   ├── Refuel.java
│   ├── RefuelRepository.java
│   ├── RefuelService.java
│   ├── RefuelController.java         ← /api/v1/refuels/*
│   ├── RefuelRequestDTO.java
│   └── RefuelResponseDTO.java
│
├── vehicleevent/                    ← Módulo de prontuário do veículo (eventos financeiros/operacionais)
│   ├── VehicleEvent.java
│   ├── VehicleEventType.java         ← Enum: FUEL, MAINTENANCE, OIL_CHANGE, CAR_WASH, TIRES, INSURANCE, TAX, DOCUMENTS, OTHER
│   ├── VehicleEventRepository.java
│   ├── VehicleEventService.java
│   ├── VehicleEventController.java   ← /api/v1/vehicle-events/*
│   └── dto/
│       ├── VehicleEventRequestDTO.java
│       └── VehicleEventResponseDTO.java
│
└── dashboard/                        ← Módulo de analytics
    ├── DashboardController.java      ← /api/v1/dashboard/*
    ├── DashboardService.java
    └── DashboardDTO.java
```

### Camadas da aplicação

```
┌─────────────────────────────────────────┐
│   Cliente Android (Kotlin + Compose)    │  ADR-010
└─────────────────┬───────────────────────┘
                  │ HTTP + Bearer access token (15min)
                  │ Refresh token rotacionado em /auth/refresh
┌─────────────────▼───────────────────────┐
│            RequestIdFilter              │  MDC requestId + X-Request-Id
├─────────────────────────────────────────┤
│        JwtAuthenticationFilter          │  Valida access token → injeta User
├─────────────────────────────────────────┤
│            Controllers                  │  /api/v1/...
├─────────────────────────────────────────┤
│              Services                   │  Regras + invariantes
├─────────────────────────────────────────┤
│            Repositories                 │  Spring Data JPA
├─────────────────────────────────────────┤
│        PostgreSQL gerenciado            │  ADR-004
└─────────────────────────────────────────┘
```

---

## 4. Padrões Arquiteturais

| Padrão | Onde é aplicado |
|---|---|
| **Modular Monolith** | Pacotes por feature (`user`, `vehicle`, `refuel`, `dashboard`) — [ADR-001](adr/ADR-001-monolito-modular.md) |
| **MVC** | Controller → Service → Repository, DTOs como contrato |
| **Repository Pattern** | Todos os `*Repository` estendem `JpaRepository` |
| **Service Layer** | Regras de negócio isoladas em `*Service` |
| **DTO Pattern** | `UserRegisterDTO`, `UserResponseDTO`, `VehicleRequestDTO`, `RefuelRequestDTO`, `DashboardDTO`, `TokenPairResponse` |
| **Filter Chain** | `RequestIdFilter` → `JwtAuthenticationFilter` antes dos controllers |
| **Builder (Lombok)** | `DashboardDTO` |
| **Stateless Auth + Stateful Refresh** | Access token JWT (stateless) + refresh token persistido em `refresh_tokens` — [ADR-003](adr/ADR-003-autenticacao-jwt.md) |
| **RFC 7807 ProblemDetail** | Respostas de erro padronizadas via `ProblemDetailWriter` + `ErrorCode` |
| **API Versioning by URL** | Prefixo `/api/v1` global — [ADR-002](adr/ADR-002-rest-versionamento.md) |

---

## 5. Autenticação e Segurança

Modelo **JWT Access + Refresh Token rotacionado** ([ADR-003](adr/ADR-003-autenticacao-jwt.md)).

### Fluxo

```
1. POST /api/v1/auth/login
   → UserService valida email + senha (BCrypt)
   → JwtUtil.generateAccessToken (TTL 15 min)
   → RefreshTokenService.issue (TTL 30 dias, hash SHA-256 em refresh_tokens)
   → Retorna: { accessToken, refreshToken, expiresIn }

2. Requisições autenticadas:
   → Header: Authorization: Bearer <accessToken>
   → JwtAuthenticationFilter valida; em falha → 401 ProblemDetail

3. Renovação:
   → POST /api/v1/auth/refresh { refreshToken }
   → Valida hash, expiração e revogação na tabela
   → ROTAÇÃO: marca anterior como replaced_by, emite novo par
   → Detecção de re-uso: se um refresh já rotacionado for reapresentado, revoga a cadeia inteira

4. Revogação:
   → POST /api/v1/auth/logout { refreshToken } → revoga o token
   → PUT /api/v1/auth/{userId}/password → revoga todas as sessões do usuário
   → RefreshTokenCleanupJob diário remove tokens expirados/revogados
```

### Detalhes do Access Token

| Propriedade | Valor |
|---|---|
| Algoritmo | HS512 |
| TTL | 15 minutos (configurável via `jwt.access-token-ttl-ms`) |
| Claims | `sub` (email), `userId` |
| Chave | `jwt.secret` — **deve ser variável de ambiente em produção** |

### Detalhes do Refresh Token

| Propriedade | Valor |
|---|---|
| TTL padrão | 30 dias (`jwt.refresh-token-ttl-ms`, 2 592 000 000 ms) |
| Armazenamento | Tabela `refresh_tokens` (hash SHA-256, nunca o plaintext) |
| Rotação | A cada `/auth/refresh` |
| Revogação | Logout, troca de senha ou detecção de re-uso |
| Limpeza | `RefreshTokenCleanupJob` (diário) |

### Endpoints públicos (sem autenticação)

| Endpoint | Método |
|---|---|
| `/api/v1/auth/register` | POST |
| `/api/v1/auth/login` | POST |
| `/api/v1/auth/refresh` | POST |
| `/v3/api-docs/**` | GET |
| `/swagger-ui/**`, `/swagger-ui.html` | GET |

Todos os demais exigem `Authorization: Bearer <accessToken>`.

### Outras proteções

- Senhas com **BCryptPasswordEncoder**, nunca em texto plano
- CORS restrito a origens conhecidas (sem `*`)
- `UserResponseDTO` nunca expõe `password`
- Ownership check em todos os endpoints de domínio (vehicle, refuel, dashboard)
- `X-Request-Id` propagado para correlacionar logs (MDC) e Sentry

### Riscos remanescentes

| # | Risco | Severidade | Mitigação prevista |
|---|---|---|---|
| 1 | `jwt.secret` com fallback hardcoded em dev (`flowfuel-dev-only-secret-...`) | Alta | Exigir env var `JWT_SECRET` em produção; remover default em deploy |
| 2 | Upload de foto **sem resize/recompressão** — aceita até 5 MB sem normalizar dimensões | Média | Adicionar Thumbnailator (512×512, JPEG q85) — bloqueador beta ([ADR-005](adr/ADR-005-storage-cloud.md)) |
| 3 | Presigned URL com TTL fixo 15min via AWS SDK v1 (dependência legada) | Baixa | Migrar para `S3Presigner` do SDK v2 e remover dep v1 |
| 4 | Endpoint proxy `GET /profile-picture` sem `ETag`/`Cache-Control` | Baixa | Adicionar headers + suporte a `If-None-Match` → 304 |
| 5 | Recuperação de senha não implementada | Média | Definir fluxo (email/SMS) ou remover endpoint |

---

## 6. Entidades e Relacionamentos

### Diagrama ER simplificado

```
┌──────────┐       ┌─────────────┐       ┌──────────┐
│  users   │ 1   N │  vehicles   │ 1   N │  refuels │
└────┬─────┘───────└─────────────┘───────└──────────┘
     │ 1
     │ N
┌────▼──────────────┐         ┌────────────────────────┐
│ refresh_tokens    │         │ user_profile_picture   │  (planejado — ADR-005)
└───────────────────┘         └────────────────────────┘
```

### User

| Campo | Tipo | Regra |
|---|---|---|
| id | Long | PK |
| email | String | UNIQUE, NOT NULL |
| password | String | NOT NULL, BCrypt |
| name | String | opcional |
| phone | String | opcional |
| profile_picture | String | **chave do objeto** no bucket S3-compatível (ex.: `profile_pictures/{userId}_foto.jpg`) — ADR-005 |
| created_at / updated_at | LocalDateTime | auto |

**Relacionamentos:** `@OneToMany(vehicles)`, `@OneToMany(refreshTokens)`.

### Vehicle

| Campo | Tipo | Regra |
|---|---|---|
| id | Long | PK |
| type | String | NOT NULL (ex: "Carro", "Moto") |
| energy_type | EnergyType | NOT NULL, persistido como **STRING** (V2 migration) |
| fuel_sub_type | String | opcional ("Gasolina", "Etanol", ...) |
| current_km | Integer | NOT NULL, ≥ 0 |
| capacity | Integer | NOT NULL, ≥ 1 |
| brand / model / color / license_plate | String | opcional |
| manufacture_year / model_year | Integer | 1886–2100 |
| photo | String | opcional |
| is_active | Boolean | default true; apenas 1 ativo por usuário |
| user_id | Long | FK → users |

### Refuel

| Campo | Tipo | Regra |
|---|---|---|
| id | Long | PK |
| odometer | Integer | NOT NULL, derivado no servidor (`vehicle.currentKm + trip`) — não enviado pelo cliente |
| km_since_last_refuel | Integer | igual ao `trip` informado na request |
| energy_amount | BigDecimal | ≥ 0.01, ≤ capacidade |
| price_per_unit | BigDecimal | dentro do range por tipo |
| total_amount | BigDecimal | `@PrePersist / @PreUpdate` (`energy × price`) |
| full_tank | Boolean | default false |
| refuel_date | LocalDateTime | `@CreationTimestamp` |
| vehicle_id | Long | FK → vehicles |

### RefreshToken

| Campo | Tipo | Regra |
|---|---|---|
| id | Long | PK |
| user_id | Long | FK → users |
| token_hash | String | SHA-256 do plaintext (único) |
| issued_at | LocalDateTime | auto |
| expires_at | LocalDateTime | NOT NULL |
| revoked_at | LocalDateTime | nullable |
| replaced_by | FK → refresh_tokens | rastreia rotação |

### EnergyType (Enum)

Define a matriz energética do veículo. Os helpers `getEnergyUnit()` / `getPriceUnit()` / `getConsumptionUnit()` em `EnergyType` retornam o rótulo padrão; para HYBRID o dashboard usa `breakdown` (ver `RefuelType` abaixo).

| Valor | Unidade energia | Unidade preço | Unidade consumo |
|---|---|---|---|
| COMBUSTION | litros | R$/litro | km/L |
| ELECTRIC | kWh | R$/kWh | km/kWh |
| HYBRID | — (ver `breakdown`) | — | — |

### RefuelType (Enum)

Classifica cada abastecimento individual:

| Valor | Aceito em | Unidade de `energyAmount` |
|---|---|---|
| FUEL | COMBUSTION, HYBRID | litros |
| ELECTRIC | ELECTRIC, HYBRID | kWh |

Inferência no `RefuelRequestDTO`:

- `COMBUSTION` → default `FUEL`
- `ELECTRIC` → default `ELECTRIC`
- `HYBRID` → **obrigatório** no request (sem default)

---

## 7. Regras de Negócio

### Usuários

- E-mail único, validado também em update
- Senha mínima de 6 caracteres, armazenada com BCrypt
- Troca de senha (`PUT /auth/{userId}/password`) **revoga todas as sessões** do usuário
- Usuário só pode operar nos próprios recursos (`ensureSelf`)

### Veículos

- Sempre pertencem a um usuário
- Apenas um veículo `is_active = true` por usuário (ativar um desativa os demais)
- Odômetro só cresce: `updateOdometer` rejeita valores menores que o atual
- Abastecimento atualiza `current_km` do veículo automaticamente
- Todo endpoint verifica propriedade

### Abastecimentos

- Entrada via `trip` (1–5000 km percorridos desde o último abastecimento); odômetro absoluto é **derivado** (`vehicle.currentKm + trip`) e nunca enviado pelo cliente
- `km_since_last_refuel = trip` (persistido); `vehicle.currentKm` é atualizado para o novo odômetro absoluto
- `total_amount = energy_amount × price_per_unit` via `@PrePersist/@PreUpdate`
- `refuelType` resolvido por inferência (COMBUSTION→FUEL, ELECTRIC→ELECTRIC) ou obrigatório (HYBRID)
- Combinações inválidas (ex.: `refuelType=ELECTRIC` em veículo COMBUSTION) ⇒ `400 BusinessRuleException`
- `energy_amount` ≤ capacidade efetiva (`capacity` para `FUEL`; `batteryCapacity` para `ELECTRIC`). Se a capacidade correspondente não estiver cadastrada, a validação é ignorada.
- Faixa de preço válida (definida pelo `refuelType` resolvido, não pelo veículo):
  - **FUEL:** R$ 0,50–15,00 / litro
  - **ELECTRIC:** R$ 0,10–5,00 / kWh
- Apenas o dono do veículo cria/edita/exclui
- Filtro por período: `startDate`, `endDate` (opcionais, `YYYY-MM-DD`)

### Dashboard

- Consumo médio só usa **tanques cheios** (`full_tank = true`)
- Requer ≥ 2 abastecimentos cheios para calcular consumo
- Para `COMBUSTION` / `ELECTRIC`: campos planos (`totalEnergy`, `averagePrice`, `averageConsumption`) preenchidos com as unidades correspondentes (`energyUnit`, `priceUnit`, `consumptionUnit`)
- Para `HYBRID`: campos planos de energia/preço/consumo são `null`; usar `breakdown.fuel` e `breakdown.electric`. `totalSpent` e `totalRefuels` continuam agregados no nível raiz

---

## 8. Documentação Completa da API

> **Base URL local:** `http://localhost:8080`
> **Prefixo de todas as rotas:** `/api/v1` ([ADR-002](adr/ADR-002-rest-versionamento.md))
> **Autenticação:** `Authorization: Bearer <accessToken>` (exceto rotas públicas)
> **Header de correlação:** toda resposta carrega `X-Request-Id` (eco do header recebido ou gerado)

### Módulo Auth — `/api/v1/auth`

#### `POST /api/v1/auth/register`

Cria um novo usuário.

**Autenticação:** Pública

**Request:**
```json
{ "email": "usuario@email.com", "password": "minimo6", "name": "Felipe Rocha" }
```

**Response 200:** `UserResponseDTO` (sem `password`)

**Erros:** `409 CONFLICT` (e-mail já existe), `400 BAD_REQUEST` (validação)

---

#### `POST /api/v1/auth/login`

Autentica e devolve o par de tokens.

**Autenticação:** Pública

**Request:**
```json
{ "email": "usuario@email.com", "password": "minimo6" }
```

**Response 200:**
```json
{
  "accessToken": "eyJhbGciOiJIUzUxMiJ9...",
  "refreshToken": "rt_...",
  "expiresIn": 900
}
```

Header de resposta: `Authorization: Bearer <accessToken>`

**Erros:** `401 UNAUTHORIZED` (credenciais inválidas)

---

#### `POST /api/v1/auth/refresh`

Rotaciona o par de tokens.

**Autenticação:** Pública (o refresh token é o segredo)

**Request:**
```json
{ "refreshToken": "rt_..." }
```

**Response 200:** novo `TokenPairResponse` (refresh anterior é invalidado)

**Erros:** `401 UNAUTHORIZED` (expirado, revogado ou re-uso detectado — neste caso a cadeia inteira é revogada)

---

#### `POST /api/v1/auth/logout`

Revoga o refresh token informado.

**Autenticação:** JWT obrigatório

**Request:**
```json
{ "refreshToken": "rt_..." }
```

**Response 204 No Content**

---

#### `PUT /api/v1/auth/{userId}/password`

Troca a senha. **Revoga todas as sessões** ([ADR-003](adr/ADR-003-autenticacao-jwt.md)).

**Autenticação:** JWT obrigatório (apenas o próprio usuário)

**Request:**
```json
{ "currentPassword": "antiga", "newPassword": "novaMin6" }
```

**Response 204 No Content**

---

#### `GET /api/v1/auth/{userId}/profile`

Busca o perfil. **Apenas o próprio usuário.**

**Response 200:** `UserResponseDTO`

---

#### `PUT /api/v1/auth/{userId}/profile`

Atualiza nome, telefone ou e-mail.

**Response 200:** `UserResponseDTO`
**Erros:** `409 CONFLICT` (e-mail em uso)

---

#### `POST /api/v1/auth/{userId}/upload-profile-picture`

Faz upload da foto de perfil. Implementado conforme [ADR-005](adr/ADR-005-storage-cloud.md) — bytes vão para um bucket S3-compatível (Backblaze B2); a coluna `users.profile_picture` guarda a **chave** do objeto.

**Autenticação:** JWT obrigatório (apenas o próprio usuário)

**Content-Type:** `multipart/form-data` com campo `file`

**Validações:**
- MIME ∈ `image/jpeg`, `image/png`, `image/webp`
- Tamanho ≤ 5 MB
- ⚠️ Sem resize obrigatório ainda — pendência tracked em ADR-005

**Comportamento:**
- Deleta a foto anterior do bucket se existir
- Faz upload da nova com chave `profile_pictures/{userId}_{nomeOriginalSanitizado}`
- Atualiza `users.profile_picture` com a chave

**Response 200:**
```json
{
  "internalUrl": "/auth/7/profile-picture",
  "signedUrl": "https://s3.us-west-002.backblazeb2.com/<bucket>/profile_pictures/7_foto.jpg?X-Amz-..."
}
```

`signedUrl` é uma URL pré-assinada com TTL de 15 minutos — cliente mobile deve revalidar periodicamente (não cachear a URL, apenas os bytes).

---

#### `GET /api/v1/auth/{userId}/profile-picture`

Proxy que baixa o objeto do bucket e devolve os bytes.

**Autenticação:** JWT obrigatório (apenas o próprio usuário)

**Response 200:** body binário + `Content-Type` da row
**Response 204:** se o usuário não tem foto

> ⚠️ Pendente: `ETag`/`Cache-Control` + suporte a `If-None-Match` → 304.

---

#### `DELETE /api/v1/auth/{userId}/profile-picture`

Remove a foto do bucket e limpa `users.profile_picture`.

**Autenticação:** JWT obrigatório (apenas o próprio usuário)

**Response 204 No Content**

---

#### `DELETE /api/v1/auth/{userId}`

Exclui a conta (cascateia veículos, abastecimentos e refresh tokens).

**Response 200**

---

### Módulo Vehicles — `/api/v1/vehicles`

> Todos os endpoints verificam que o veículo pertence ao usuário autenticado.

| Método | Rota | Descrição | Tipo de resposta |
|---|---|---|---|
| POST | `/api/v1/vehicles` | Cria veículo | `VehicleResponseDTO` |
| GET | `/api/v1/vehicles?page=&size=&sort=` | Lista paginada do usuário (default size=20, sort=`createdAt,desc`, max size=100) | `PageResponseDTO<VehicleResponseDTO>` |
| GET | `/api/v1/vehicles/active` | Veículo ativo | `VehicleResponseDTO` |
| GET | `/api/v1/vehicles/{id}` | Detalhe | `VehicleResponseDTO` |
| PUT | `/api/v1/vehicles/{id}` | Atualiza | `VehicleResponseDTO` |
| PUT | `/api/v1/vehicles/{id}/odometer?currentKm=...` | Atualiza odômetro (somente crescente) | `VehicleResponseDTO` |
| PUT | `/api/v1/vehicles/{id}/active` | Define como ativo (desativa os demais) | `204` |
| DELETE | `/api/v1/vehicles/{id}` | Remove (cascade para abastecimentos) | `200` |

**`POST /api/v1/vehicles` — request body:**
```json
{
  "type": "Carro",
  "energyType": "COMBUSTION",
  "fuelSubType": "Gasolina",
  "currentKm": 45000,
  "capacity": 55,
  "batteryCapacity": null,
  "brand": "Volkswagen",
  "model": "Gol",
  "manufactureYear": 2020,
  "modelYear": 2021,
  "color": "Branco",
  "licensePlate": "ABC-1234"
}
```

| Campo | Obrigatório | Validação |
|---|---|---|
| type | Sim | `@NotBlank` |
| energyType | Sim | `@NotNull` (COMBUSTION / ELECTRIC / HYBRID) |
| currentKm | Sim | ≥ 0 |
| capacity | Sim | ≥ 1 (litros do tanque) |
| batteryCapacity | Não | kWh — recomendado para ELECTRIC/HYBRID |
| manufactureYear / modelYear | Não | 1886–2100 |

---

### Módulo Refuels — `/api/v1/refuels`

> Verificação de propriedade na cadeia `refuel → vehicle → user`.

#### `POST /api/v1/refuels`

**Request:**
```json
{
  "vehicleId": 1,
  "trip": 450,
  "energyAmount": 40.5,
  "pricePerUnit": 5.89,
  "fullTank": true,
  "refuelType": "FUEL"
}
```

| Campo | Obrigatório | Validação |
|---|---|---|
| vehicleId | Sim | `@NotNull` |
| trip | Sim | `[1..5000]` — km percorridos desde o último abastecimento |
| energyAmount | Sim | ≥ 0,01 e ≤ capacidade efetiva (tanque ou bateria) |
| pricePerUnit | Sim | dentro do range pelo `refuelType` resolvido |
| fullTank | Não | default `false` |
| refuelType | Depende | `FUEL` ou `ELECTRIC`. Obrigatório para HYBRID; inferido nos demais. Combinação inválida ⇒ 400 |

**Cálculo automático do odômetro:** o servidor deriva `odometer = vehicle.currentKm + trip` (ou `lastRefuel.odometer + trip` quando já houver histórico) e persiste o valor absoluto, mantendo `kmSinceLastRefuel = trip`. O `vehicle.currentKm` é atualizado automaticamente após o save.

**Response 200:** `RefuelResponseDTO` com `odometer` (absoluto, derivado), `kmSinceLastRefuel` e `totalAmount` calculados.

**Erros típicos:** `400` (validação `trip`, `energyAmount`/`price` fora do range, veículo sem `currentKm` inicial), `403` (não dono), `404` (veículo não existe).

#### Demais rotas

| Método | Rota | Descrição | Tipo de resposta |
|---|---|---|---|
| GET | `/api/v1/refuels/vehicle/{vehicleId}?startDate&endDate&page=&size=` | Lista paginada por veículo com filtro de período (default size=20, max=100) | `PageResponseDTO<RefuelResponseDTO>` |
| GET | `/api/v1/refuels/{id}` | Detalhe | `RefuelResponseDTO` |
| PUT | `/api/v1/refuels/{id}` | Atualiza (mesmas regras) | `RefuelResponseDTO` |
| DELETE | `/api/v1/refuels/{id}` | Remove | `200` |

> **Sincronização incremental Online-First** ([ADR-012](adr/ADR-012-sincronizacao-online-first.md)): o cliente Android deve favorecer query string `updatedAfter=<ISO-8601>` quando o endpoint suportar — variantes serão adicionadas progressivamente sob compatibilidade [ADR-002](adr/ADR-002-rest-versionamento.md).

---

### Módulo Dashboard — `/api/v1/dashboard`

#### `GET /api/v1/dashboard/vehicle/{vehicleId}`

Métricas consolidadas do veículo. **Verifica propriedade** (corrigido — o controller recebe `@AuthenticationPrincipal User` e o service valida ownership).

**Response 200 — `COMBUSTION` / `ELECTRIC`:**
```json
{
  "vehicleId": 1,
  "energyType": "COMBUSTION",
  "totalRefuels": 15,
  "totalSpent": 3572.45,
  "totalEnergy": 605.00,
  "averagePrice": 5.90,
  "averageConsumption": 12.35,
  "energyUnit": "litros",
  "priceUnit": "R$/litro",
  "consumptionUnit": "km/L",
  "breakdown": null,
  "lastRefuelDate": "2026-05-15",
  "lastOdometer": 52000
}
```

Para `ELECTRIC`, as três `*Unit` mudam para `kWh` / `R$/kWh` / `km/kWh` e a semântica de `totalEnergy` passa a ser kWh.

**Response 200 — `HYBRID`:**
```json
{
  "vehicleId": 7,
  "energyType": "HYBRID",
  "totalRefuels": 30,
  "totalSpent": 5090.60,
  "totalEnergy": null,
  "averagePrice": null,
  "averageConsumption": null,
  "energyUnit": null,
  "priceUnit": null,
  "consumptionUnit": null,
  "breakdown": {
    "fuel": {
      "totalEnergy": 820.50,
      "totalSpent": 4860.10,
      "averagePrice": 5.92,
      "averageConsumption": 14.1,
      "energyUnit": "litros",
      "priceUnit": "R$/litro",
      "consumptionUnit": "km/L"
    },
    "electric": {
      "totalEnergy": 189.75,
      "totalSpent": 230.50,
      "averagePrice": 1.21,
      "averageConsumption": 6.4,
      "energyUnit": "kWh",
      "priceUnit": "R$/kWh",
      "consumptionUnit": "km/kWh"
    }
  },
  "lastRefuelDate": "2026-05-15",
  "lastOdometer": 52000
}
```

| Campo | Tipo | Descrição |
|---|---|---|
| vehicleId | Long | ID |
| energyType | EnergyType | Define o formato da resposta (plano vs. `breakdown`) |
| totalRefuels | Long | Total de abastecimentos (todos os tipos) |
| totalSpent | BigDecimal | Σ `total_amount` (sempre presente) |
| totalEnergy | BigDecimal? | Σ `energy_amount`. `null` para HYBRID |
| averagePrice | BigDecimal? | Média de `price_per_unit`. `null` para HYBRID |
| averageConsumption | Double? | km por unidade de energia (≥ 2 tanques cheios). `null` para HYBRID |
| energyUnit / priceUnit / consumptionUnit | String? | Rótulos da unidade. `null` para HYBRID |
| breakdown | HybridBreakdownDTO? | Preenchido apenas para HYBRID |
| lastRefuelDate | LocalDate | Último abastecimento |
| lastOdometer | Integer | Odômetro do último abastecimento |

`breakdown.fuel` e `breakdown.electric` seguem o mesmo schema (`FuelMetrics`): `totalEnergy`, `totalSpent`, `averagePrice`, `averageConsumption`, `energyUnit`, `priceUnit`, `consumptionUnit`.

---

### Módulo VehicleEvents — `/api/v1/vehicle-events`

#### VehicleEvent (Prontuário do Veículo)

`VehicleEvent` representa **eventos financeiros e operacionais** associados a um veículo (manutenções, troca de óleo, lavagem, pneus, seguro, IPVA/licenciamento, documentos, abastecimentos avulsos registrados como despesa, etc.). Funciona como um **prontuário/diário** do veículo.

#### O que VehicleEvent NÃO é

- **NÃO substitui `Refuel`** — abastecimentos operacionais continuam em `/api/v1/refuels`.
- **NÃO calcula consumo** nem produz métricas derivadas (km/L, km/kWh).
- **NÃO atualiza `vehicle.currentKm`** — o odômetro do veículo é movido apenas por `Refuel` (ou `PUT /vehicles/{id}/odometer`).
- `amount` é **input direto** do cliente (valor pago em R$), sem cálculo automático.
- `odometer` é **opcional e meramente informativo** — registrado para histórico, não usado em métricas.

#### Separação de domínio: Refuel × VehicleEvent

| Aspecto | `Refuel` | `VehicleEvent` |
|---|---|---|
| Propósito | Abastecimento operacional | Histórico financeiro / prontuário |
| Atualiza `vehicle.currentKm` | Sim (derivado de `trip`) | Não |
| Cálculo de consumo | Sim (km/L, km/kWh) | Não |
| Entra no Dashboard de métricas | Sim | Não (apenas listagem) |
| Tipos | `FUEL`, `ELECTRIC` | `FUEL`, `MAINTENANCE`, `OIL_CHANGE`, `CAR_WASH`, `TIRES`, `INSURANCE`, `TAX`, `DOCUMENTS`, `OTHER` |
| Valor | Derivado (`energy × price`) | Input direto (`amount`) |
| Eventos avulsos | Não | Sim |

#### Endpoints

> Todos os endpoints validam que o veículo pertence ao usuário autenticado (cadeia `vehicleEvent → vehicle → user`).

| Método | Rota | Descrição | Tipo de resposta |
|---|---|---|---|
| POST | `/api/v1/vehicle-events` | Cria um evento | `VehicleEventResponseDTO` |
| GET | `/api/v1/vehicle-events/{id}` | Detalhe | `VehicleEventResponseDTO` |
| GET | `/api/v1/vehicle-events/vehicle/{vehicleId}?type=&startDate=&endDate=&page=&size=` | Lista paginada por veículo, com filtros opcionais | `PageResponseDTO<VehicleEventResponseDTO>` |
| PUT | `/api/v1/vehicle-events/{id}` | Atualiza | `VehicleEventResponseDTO` |
| DELETE | `/api/v1/vehicle-events/{id}` | Remove | `200` |

**Filtros do GET por veículo:**

| Parâmetro | Tipo | Descrição |
|---|---|---|
| `type` | `VehicleEventType` | Filtra por categoria (ex.: `MAINTENANCE`) |
| `startDate` | `YYYY-MM-DD` | Data inicial inclusiva (`eventDate ≥ startDate`) |
| `endDate` | `YYYY-MM-DD` | Data final inclusiva (`eventDate ≤ endDate`) |

**`POST /api/v1/vehicle-events` — request body:**

```json
{
  "vehicleId": 1,
  "type": "MAINTENANCE",
  "amount": 380.00,
  "eventDate": "2026-05-20",
  "odometer": 52340,
  "description": "Troca de pastilhas de freio dianteiras"
}
```

| Campo | Obrigatório | Validação |
|---|---|---|
| `vehicleId` | Sim | `@NotNull` — veículo do usuário |
| `type` | Sim | `@NotNull` — enum `VehicleEventType` |
| `amount` | Sim | ≥ 0.01, até 2 casas decimais |
| `eventDate` | Sim | `@PastOrPresent` |
| `odometer` | Não | ≥ 0 — apenas informativo, não altera `vehicle.currentKm` |
| `description` | Não | até 2000 caracteres |

#### Decisões arquiteturais

- **Módulos separados (não fundir com `Refuel`).** `Refuel` carrega invariantes de odômetro, capacidade efetiva e faixa de preço — regras irrelevantes para um IPVA ou uma troca de óleo. Fundir os domínios obrigaria a tornar essas regras opcionais, enfraquecendo a integridade de `Refuel`.
- **Sem cálculos derivados em `VehicleEvent`.** Mantém o módulo simples e o `amount` como fonte de verdade. Métricas continuam sendo responsabilidade exclusiva de `Refuel` + `Dashboard`.
- **`odometer` informativo, não autoritativo.** Evita corrida entre dois módulos atualizando o mesmo campo do veículo. `Refuel` permanece a única fonte que move `vehicle.currentKm`.
- **Timeline unificada (futuro).** Caso o app precise exibir um histórico cronológico único (abastecimentos + manutenções + impostos), a unificação será feita **apenas na camada de agregação/API** (ex.: novo endpoint `GET /api/v1/vehicles/{id}/timeline`), sem fundir entidades nem alterar contratos existentes.

---

### Formato padrão de erros — RFC 7807 (`ProblemDetail`)

Todas as respostas de erro usam o catálogo `ErrorCode` + serializador `ProblemDetailWriter`:

```json
{
  "type": "https://flowfuel.app/errors/RESOURCE_NOT_FOUND",
  "title": "Resource not found",
  "status": 404,
  "detail": "Vehicle 42 não encontrado para o usuário 7",
  "code": "RESOURCE_NOT_FOUND",
  "requestId": "5a2b...",
  "timestamp": "2026-05-23T10:00:00Z"
}
```

Header de correlação: `X-Request-Id: 5a2b...` (gerado pelo `RequestIdFilter` se não houver na entrada).

---

## 9. Fluxo Principal da Aplicação

### Fluxo completo: novo usuário → primeiro abastecimento

```
[1] CADASTRO
    POST /api/v1/auth/register     { email, password, name }
    ← 200 UserResponseDTO

[2] LOGIN
    POST /api/v1/auth/login        { email, password }
    ← 200 { accessToken, refreshToken, expiresIn: 900 }

[3] CADASTRAR VEÍCULO
    POST /api/v1/vehicles          Bearer <accessToken>
    ← 200 Vehicle

[4] REGISTRAR ABASTECIMENTO
    POST /api/v1/refuels           { vehicleId, trip, energyAmount, pricePerUnit, fullTank }
    ← 200 Refuel (odometer, kmSinceLastRefuel, totalAmount calculados)
    [automático] vehicle.currentKm ← vehicle.currentKm + trip

[5] VER DASHBOARD
    GET  /api/v1/dashboard/vehicle/{id}
    ← averageConsumption = 0.0 (precisa de ≥ 2 cheios)

[6] SEGUNDO ABASTECIMENTO CHEIO → dashboard passa a calcular consumo

[7] ACCESS TOKEN EXPIRA (15 min) → cliente chama
    POST /api/v1/auth/refresh      { refreshToken }
    ← novo par; refresh antigo é invalidado
```

### Fluxo de autenticação

```
Requisição → RequestIdFilter (MDC + X-Request-Id)
           → JwtAuthenticationFilter
                 ├── Sem Bearer? → 401 ProblemDetail
                 ├── Token inválido/expirado? → 401 ProblemDetail
                 ├── Extrai email → UserRepository.findByEmail
                 └── Seta SecurityContext (User como principal, ROLE_USER)
           → Controller (@AuthenticationPrincipal User)
           → Service (verifica ownership) → Response
```

### Fluxo de rotação de refresh token

```
POST /api/v1/auth/refresh { refreshToken }
   │
   ├── hash SHA-256 → busca em refresh_tokens
   ├── token não encontrado / expirado / revogado → 401
   ├── token já tinha replaced_by? → RE-USO: revoga toda a cadeia → 401
   ├── marca revoked_at + replaced_by = novo
   └── emite novo par (access 15min, refresh 30d)
```

---

## 10. Banco de Dados

### Produção — PostgreSQL gerenciado ([ADR-004](adr/ADR-004-banco-postgresql.md))

- Provedor recomendado: **Railway managed** (alternativa: Neon)
- Backup automático, HA, patches de segurança automáticos
- Conexão via env vars; `spring.jpa.hibernate.ddl-auto=validate`
- Schema gerenciado por **Flyway** (`src/main/resources/db/migration`)

### Migrations atuais

| Versão | Descrição |
|---|---|
| `V1__baseline.sql` | Tabelas `users`, `vehicles`, `refuels` |
| `V2__energy_type_to_string.sql` | Converte `energy_type` de ordinal para `STRING` |
| `V3__refresh_tokens.sql` | Tabela `refresh_tokens` (ADR-003) |
| `V5__vehicle_events.sql` | Tabela `vehicle_events` (prontuário — eventos financeiros/operacionais) |

> A coluna `users.profile_picture` (já existente desde V1) passou a armazenar a **chave** do objeto no bucket S3-compatível ([ADR-005](adr/ADR-005-storage-cloud.md)) — não foi necessária nova migration.

### Dev / testes

- **Preferencial**: Postgres via **Testcontainers** (paridade com produção)
- H2 ainda aceito em testes leves, mas Testcontainers é o caminho oficial

```properties
# Exemplo produção
spring.datasource.url=jdbc:postgresql://HOST:5432/flowfuel
spring.datasource.username=...
spring.datasource.password=...
spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect
spring.jpa.hibernate.ddl-auto=validate
spring.flyway.enabled=true
jwt.secret=${JWT_SECRET}
jwt.access-token-ttl-ms=900000
jwt.refresh-token-ttl-ms=2592000000
sentry.dsn=${SENTRY_DSN}
```

### Estratégia de persistência

- `GenerationType.IDENTITY` para PKs
- `CascadeType.ALL` em User→Vehicles e `orphanRemoval=true` em Vehicle→Refuels
- `@PrePersist/@PreUpdate` em `Refuel` (cálculo de `totalAmount`)
- `@CreationTimestamp / @UpdateTimestamp` em timestamps

---

## 11. Observabilidade e Tratamento de Erros

Implementado conforme [ADR-008](adr/ADR-008-observabilidade-logs-sentry.md).

### Logs estruturados (JSON)

- `logback-spring.xml` configurado para JSON em produção
- MDC: `requestId`, `userId` (quando autenticado)
- stdout (capturado pelo Railway / sink externo)

### Sentry

- SDK + appender Logback plugados
- DSN via env var `sentry.dsn`
- Captura exceções não tratadas + breadcrumbs com `requestId`
- **Pendente**: definir DSN real do projeto e instrumentar Android com o mesmo projeto Sentry

### Uptime

- Endpoint `/` (HomeController) responde para healthchecks externos
- **Pendente**: configurar UptimeRobot (ping a cada 5min, alerta no Slack)

### Catálogo de erros + `ProblemDetail`

- `ErrorCode` centraliza códigos (`RESOURCE_NOT_FOUND`, `CONFLICT`, `BUSINESS_RULE`, `FORBIDDEN`, etc.)
- `ProblemDetailWriter` serializa no formato RFC 7807 + `requestId` + `timestamp`
- `RequestIdFilter` ecoa o header `X-Request-Id` em todas as respostas

---

## 12. Infraestrutura, Deploy e CI/CD

### Hospedagem — Railway PaaS ([ADR-006](adr/ADR-006-infraestrutura-paas.md))

- Backend em uma instância
- PostgreSQL gerenciado no mesmo projeto
- Custo previsto: ~US$ 15/mês (compute + DB)
- **Pendente**: versionar artefato de deploy (`nixpacks.toml` ou `Dockerfile`) e criar ambiente `staging`

### CI/CD ([ADR-007](adr/ADR-007-ci-cd-minimalista.md)) — pendente

Plano:
- `.github/workflows/ci.yml` rodando `./mvnw verify` em todo PR
- Branch `staging` → deploy automático para desenv.api.flowfuel.app
- Branch `main` → deploy para produção **com aprovação manual**
- Secrets via GitHub Secrets / Railway

### Cache ([ADR-009](adr/ADR-009-cache-caffeine.md))

Caffeine **não implementado** — adiado por [ADR-013](adr/ADR-013-escalabilidade-scale-when-it-hurts.md). Será introduzido quando latência do dashboard ou carga do banco virarem problema medido.

---

## 13. Sincronização Mobile (Online-First)

Decisão atual: **Online-First com Cache Agressivo** ([ADR-012](adr/ADR-012-sincronizacao-online-first.md), status PROPOSED — confirmado para o MVP).

### Princípios

- O app **assume rede disponível**; offline é estado degradado
- **Leituras** usam cache local (Room) com TTL ~15min e refresh em background
- **Escritas** (criar/editar abastecimento, atualizar perfil etc.) exigem rede; quando offline, a UI desabilita a ação com mensagem clara
- Sem fila de escrita offline na v1 (entra em uma futura v1.1 caso usuários reclamem)
- Sem CRDT / resolução de conflitos — modelo single-writer por usuário

### Impacto na API

- Endpoints de leitura tendem a expor `updatedAfter` (sincronização incremental) — adições compatíveis sob [ADR-002](adr/ADR-002-rest-versionamento.md)
- Resposta inclui `updated_at` em entidades sincronizáveis
- Cliente Android usa Room ([ADR-011](adr/ADR-011-room-persistencia-local.md)) como cache local; backend permanece a fonte de verdade

### Quando reavaliar para Offline-First

- Reclamações reais de uso em rede ruim
- Métrica de retentativa/erro de escrita acima do tolerável
- Demanda explícita de funcionar 100% offline (registrar abastecimento sem rede)

---

## 14. Como Rodar o Projeto

### Pré-requisitos

- Java 21+
- Maven (ou o wrapper `mvnw` do repositório)
- Docker (para Postgres via Testcontainers / `docker compose`)

### Execução local

```bash
# Clonar
git clone <url-do-repo>
cd flowfuel

# Subir Postgres local (recomendado, paridade com produção)
docker compose up -d postgres   # quando o compose for incluído

# Compilar e rodar
./mvnw spring-boot:run

# Ou JAR
./mvnw clean package
java -jar target/flowfuel-0.3.3-SNAPSHOT.jar
```

### Variáveis de ambiente importantes

| Variável | Uso |
|---|---|
| `JWT_SECRET` | Chave HS512 do access token (obrigatória em prod) |
| `JWT_ACCESS_TOKEN_TTL_MS` | TTL do access token (padrão 900 000 = 15 min) |
| `JWT_REFRESH_TOKEN_TTL_MS` | TTL do refresh token (padrão 2 592 000 000 = 30 dias) |
| `REFRESH_TOKEN_CLEANUP_ENABLED` | Liga/desliga `RefreshTokenCleanupJob` (default `true`) |
| `REFRESH_TOKEN_CLEANUP_CRON` | Cron do cleanup (default `0 0 3 * * *`) |
| `REFRESH_TOKEN_RETENTION_DAYS` | Janela de retenção de tokens revogados (default 30) |
| `APP_CORS_ALLOWED_ORIGINS` | Lista CSV de origens permitidas (sem `*` com credentials) |
| `SPRING_DATASOURCE_*` | Conexão com Postgres |
| `SPRING_PROFILES_ACTIVE` | Perfil ativo (default `dev`; usar `prod` em produção) |
| `SENTRY_DSN` | DSN do Sentry (opcional em dev) |
| `B2_S3_ENDPOINT` | Endpoint S3-compatível (ex.: `https://s3.us-west-002.backblazeb2.com`) — ADR-005 |
| `B2_S3_REGION` | Região do bucket (default `us-west-002`) |
| `B2_S3_ACCESS_KEY` | Application Key ID do Backblaze B2 |
| `B2_S3_SECRET` | Application Key do Backblaze B2 |
| `B2_BUCKET_NAME` | Nome do bucket de fotos de perfil |

### Endpoints úteis

| URL | Descrição |
|---|---|
| `http://localhost:8080/` | Healthcheck |
| `http://localhost:8080/swagger-ui/index.html` | Swagger UI |
| `http://localhost:8080/v3/api-docs` | OpenAPI JSON |

### Testes

```bash
./mvnw test
```

Os testes de integração devem usar **Testcontainers (Postgres)** para evitar divergência entre H2 e PG em produção.

---

## 15. Qualidade do Código e Melhorias Pendentes

### Pontos positivos

- Estrutura modular por feature alinhada ao [ADR-001](adr/ADR-001-monolito-modular.md)
- Versionamento `/api/v1` global ([ADR-002](adr/ADR-002-rest-versionamento.md))
- JWT + Refresh Token rotacionado, com detecção de re-uso ([ADR-003](adr/ADR-003-autenticacao-jwt.md))
- DTOs separam contrato de entidade; `UserResponseDTO` esconde a senha
- `ProblemDetail` padronizado + `X-Request-Id` correlacionando logs/Sentry
- Flyway versionando o schema

### Pendências priorizadas (alinhadas às próximas ações em [adr/INDEX.md](adr/INDEX.md))

#### Semana 1–2 (Backend Hardening)
- [x] **Upload de foto de perfil funcional** ([ADR-005](adr/ADR-005-storage-cloud.md)) — `StorageService` + `S3StorageService` (Backblaze B2), endpoints `POST/GET/DELETE /auth/{userId}/profile-picture`, presigned URL com TTL 15min
- [ ] **Resize obrigatório** (512×512, JPEG q85) via Thumbnailator — bloqueador beta Android ([ADR-005](adr/ADR-005-storage-cloud.md))
- [ ] `ETag`/`Cache-Control` + suporte a `If-None-Match` → 304 no endpoint proxy de foto
- [ ] Migrar presigned URL de AWS SDK v1 para `S3Presigner` do SDK v2; remover dependência v1
- [ ] **CI mínima** `.github/workflows/ci.yml` rodando `./mvnw verify` em PRs ([ADR-007](adr/ADR-007-ci-cd-minimalista.md))
- [ ] **Artefato de deploy versionado** (`nixpacks.toml` ou `Dockerfile`) + ambiente `staging` ([ADR-006](adr/ADR-006-infraestrutura-paas.md))
- [ ] **DSN real do Sentry** + UptimeRobot ([ADR-008](adr/ADR-008-observabilidade-logs-sentry.md))
- [ ] Remover fallback hardcoded de `jwt.secret`; exigir env var em prod

#### Semana 3 (Decision Point)
- [ ] Confirmar Online-First vs Offline-First ([ADR-012](adr/ADR-012-sincronizacao-online-first.md)) — decisão atual: **Online-First**
- [ ] Documentar parâmetros `updatedAfter` por endpoint sincronizável

#### Semana 4–10 (Android)
- [ ] Setup Android (Kotlin + Compose + MVVM — [ADR-010](adr/ADR-010-android-kotlin-compose-mvvm.md))
- [ ] Room schema espelhando entidades do backend ([ADR-011](adr/ADR-011-room-persistencia-local.md))
- [ ] Camada de sync Online-First com TTL e invalidation manual ([ADR-012](adr/ADR-012-sincronizacao-online-first.md))

#### Melhorias contínuas
- [ ] Migrar testes legados para Testcontainers (Postgres)
- [ ] Implementar (ou remover) fluxo de recuperação de senha
- [ ] Paginação nos endpoints de listagem (`PageResponseDTO` já existe em `common/`)
- [ ] Endpoint de estatísticas mensais (query já existe no repository)
- [ ] Testes unitários adicionais para `RefuelService` e `VehicleService`

---

## 16. Mapa de ADRs

| ADR | Tema | Status atual | Implementação |
|---|---|---|---|
| [001](adr/ADR-001-monolito-modular.md) | Monolito Modular | ACCEPTED | ✅ Implementado |
| [002](adr/ADR-002-rest-versionamento.md) | REST + Versionamento `/api/v1` | ACCEPTED | ✅ Implementado |
| [003](adr/ADR-003-autenticacao-jwt.md) | JWT + Refresh Token | IMPLEMENTED | ✅ Implementado |
| [004](adr/ADR-004-banco-postgresql.md) | PostgreSQL gerenciado | ACCEPTED | ✅ Implementado |
| [005](adr/ADR-005-storage-cloud.md) | Storage de foto em Backblaze B2 (S3) | IMPLEMENTED | 🟢 Upload/Get/Delete funcionais — falta resize + ETag |
| [006](adr/ADR-006-infraestrutura-paas.md) | PaaS (Railway) | ACCEPTED | 🟡 Decidido, sem artefato versionado |
| [007](adr/ADR-007-ci-cd-minimalista.md) | CI/CD minimalista | ACCEPTED | ⛔ Não implementado |
| [008](adr/ADR-008-observabilidade-logs-sentry.md) | Logs + Sentry | IMPLEMENTED | 🟡 Backend pronto; falta DSN/UptimeRobot |
| [009](adr/ADR-009-cache-caffeine.md) | Cache Caffeine | ACCEPTED | ⛔ Adiado por ADR-013 |
| [010](adr/ADR-010-android-kotlin-compose-mvvm.md) | Android Kotlin + Compose + MVVM | PROPOSED | — |
| [011](adr/ADR-011-room-persistencia-local.md) | Room | PROPOSED | — |
| [012](adr/ADR-012-sincronizacao-online-first.md) | **Online-First** | PROPOSED (em vigor) | 🟢 Backend pronto |
| [013](adr/ADR-013-escalabilidade-scale-when-it-hurts.md) | Scale When It Hurts | ACCEPTED | ✅ Política em vigor |

---

*Documentação revisada em 2026-05-23 para refletir os ADRs vigentes e o estado atual do código.*
