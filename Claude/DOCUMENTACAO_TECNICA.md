# FlowFuel — Documentação Técnica Completa

> Gerado em: 2026-05-19  
> Versão da API: 0.0.1-SNAPSHOT  
> Autor da análise: Claude Sonnet 4.6

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
11. [Como Rodar o Projeto](#11-como-rodar-o-projeto)
12. [Qualidade do Código e Melhorias Sugeridas](#12-qualidade-do-código-e-melhorias-sugeridas)

---

## 1. Resumo do Projeto e Objetivo

**FlowFuel** é uma API REST Backend para gerenciamento de combustível e energia de veículos. O sistema permite que usuários cadastrem seus veículos, registrem abastecimentos (ou recargas elétricas) e acompanhem métricas de consumo por meio de um dashboard analítico.

### Objetivo principal

Fornecer uma plataforma mobile-first (consumida por um app React Native / Expo) que centraliza o histórico de abastecimentos de um ou mais veículos por usuário, calculando automaticamente:

- Consumo médio (km/L ou km/kWh)
- Total gasto em combustível/energia
- Preço médio por unidade
- Odômetro atualizado automaticamente a cada abastecimento

### Público-alvo

Motoristas que desejam monitorar o custo e eficiência energética de seus veículos (carros a combustão, elétricos ou híbridos).

---

## 2. Tecnologias Utilizadas

| Categoria | Tecnologia | Versão |
|---|---|---|
| Linguagem | Java | 21 |
| Framework | Spring Boot | 3.5.7 |
| Segurança | Spring Security + JWT (jjwt) | 0.11.5 |
| ORM / Persistência | Spring Data JPA + Hibernate | via Spring Boot |
| Banco (produção) | PostgreSQL | runtime |
| Banco (testes / dev) | H2 (in-memory) | runtime |
| Documentação | SpringDoc OpenAPI (Swagger UI) | 2.0.4 |
| Redução de boilerplate | Lombok | via Spring Boot |
| Validação | Spring Validation (Bean Validation) | via Spring Boot |
| Build | Maven Wrapper (mvnw) | 3.x |
| Servidor HTTP | Tomcat embarcado | via Spring Boot |

### Origens CORS permitidas

| Origem | Uso |
|---|---|
| `http://localhost:8081` | App React Native (Expo dev) |
| `http://192.168.1.2:8081` | App em rede local |
| `http://localhost:5173` | Frontend Vite/React (web) |

---

## 3. Arquitetura do Projeto

### Estrutura de pacotes

```
com.devappmobile.flowfuel/
├── FlowFuelApplication.java        ← Entry point Spring Boot
├── HomeController.java             ← Health check / página inicial
│
├── config/                         ← Configurações globais
│   ├── JwtUtil.java                ← Geração e validação de tokens JWT
│   ├── JwtAuthenticationFilter.java ← Filtro de autenticação (OncePerRequestFilter)
│   ├── SecurityConfig.java         ← Regras de segurança HTTP + CORS + BCrypt
│   └── SwaggerConfig.java          ← Configuração OpenAPI / Swagger UI
│
├── user/                           ← Módulo de usuários
│   ├── User.java                   ← Entidade JPA
│   ├── UserRepository.java         ← Spring Data Repository
│   ├── UserService.java            ← Lógica de negócio
│   ├── UserController.java         ← Endpoints REST (/api/auth/*)
│   └── LoginResponse.java          ← DTO de resposta do login
│
├── vehicle/                        ← Módulo de veículos
│   ├── Vehicle.java                ← Entidade JPA
│   ├── EnergyType.java             ← Enum: COMBUSTION, ELECTRIC, HYBRID
│   ├── VehicleRepository.java      ← Spring Data Repository
│   ├── VehicleService.java         ← Lógica de negócio
│   ├── VehicleController.java      ← Endpoints REST (/api/vehicles/*)
│   └── dto/
│       └── VehicleRequestDTO.java  ← DTO de entrada com validações
│
├── refuel/                         ← Módulo de abastecimentos
│   ├── Refuel.java                 ← Entidade JPA
│   ├── RefuelRepository.java       ← Spring Data Repository (com @Query)
│   ├── RefuelService.java          ← Lógica de negócio + validações
│   ├── RefuelController.java       ← Endpoints REST (/api/refuels/*)
│   └── RefuelRequestDTO.java       ← DTO de entrada com validações
│
└── dashboard/                      ← Módulo de analytics
    ├── DashboardController.java    ← Endpoints REST (/api/dashboard/*)
    ├── DashboardService.java       ← Cálculo de métricas
    └── DashboardDTO.java           ← DTO de resposta do dashboard
```

### Camadas da aplicação

```
┌─────────────────────────────────────────┐
│         Cliente (App / Browser)         │
└─────────────────┬───────────────────────┘
                  │ HTTP + Bearer Token
┌─────────────────▼───────────────────────┐
│        JwtAuthenticationFilter          │  ← Intercepta toda requisição
│  (valida token → carrega User no ctx)   │
└─────────────────┬───────────────────────┘
                  │
┌─────────────────▼───────────────────────┐
│            Controllers                  │  ← Recebem requisição + @AuthenticationPrincipal
└─────────────────┬───────────────────────┘
                  │
┌─────────────────▼───────────────────────┐
│              Services                   │  ← Regras de negócio + validações
└─────────────────┬───────────────────────┘
                  │
┌─────────────────▼───────────────────────┐
│            Repositories                 │  ← Acesso a dados (Spring Data JPA)
└─────────────────┬───────────────────────┘
                  │
┌─────────────────▼───────────────────────┐
│        Banco de Dados (H2 / PG)         │
└─────────────────────────────────────────┘
```

---

## 4. Padrões Arquiteturais

| Padrão | Onde é aplicado |
|---|---|
| **MVC (Model-View-Controller)** | Controller → Service → Repository, com DTOs como "View layer" |
| **Repository Pattern** | Todos os `*Repository` estendem `JpaRepository` |
| **Service Layer** | Toda regra de negócio está isolada nos `*Service` |
| **DTO Pattern** | `VehicleRequestDTO`, `RefuelRequestDTO`, `DashboardDTO`, `LoginResponse` separam entrada/saída da entidade |
| **Filter Chain (Middleware)** | `JwtAuthenticationFilter` intercepta todas as requisições antes dos controllers |
| **Builder Pattern** | `DashboardDTO` usa `@Builder` do Lombok |
| **Stateless Auth (JWT)** | `SessionCreationPolicy.STATELESS` — sem sessão no servidor |

---

## 5. Autenticação e Segurança

### Fluxo JWT

```
1. POST /api/auth/login
   → UserService valida email + senha (BCrypt)
   → JwtUtil.generateToken(email, userId)
   → Retorna: { "token": "eyJ..." }

2. Requisições subsequentes:
   → Header: Authorization: Bearer eyJ...
   → JwtAuthenticationFilter intercepta
   → jwtUtil.validateToken(token) → true
   → jwtUtil.extractEmail(token) → busca User no banco
   → Seta autenticação no SecurityContextHolder
   → Controller recebe User via @AuthenticationPrincipal
```

### Detalhes do Token

| Propriedade | Valor |
|---|---|
| Algoritmo | HS512 |
| Expiração | 24 horas (86.400.000 ms) |
| Claims | `sub` (email), `userId` (Long) |
| Chave padrão | `flowfuel-app-secret-key-stable-production-2026` (configurável via `jwt.secret`) |

### Endpoints públicos (sem autenticação)

| Endpoint | Método |
|---|---|
| `/api/auth/register` | POST |
| `/api/auth/login` | POST |
| `/v3/api-docs/**` | GET |
| `/swagger-ui/**` | GET |
| `/swagger-ui.html` | GET |

Todos os demais endpoints exigem `Authorization: Bearer <token>`.

### Hash de senhas

Utiliza **BCryptPasswordEncoder** — senhas nunca são armazenadas em texto plano.

### CORS

Configurado globalmente via `CorsConfigurationSource`. Permite origens específicas (não `*` globalmente) com `allowCredentials: true` e todos os métodos HTTP.

### Riscos identificados

| # | Risco | Severidade |
|---|---|---|
| 1 | Chave JWT padrão hardcoded no código (`flowfuel-app-secret-key-stable-production-2026`) | Alta |
| 2 | `@CrossOrigin(origins = "*")` nos controllers sobrepõe a config global de CORS restritiva | Média |
| 3 | Dashboard não verifica se o veículo pertence ao usuário autenticado | Média |
| 4 | Upload de foto de perfil salva apenas o path local — sem validação de tipo/tamanho | Média |
| 5 | H2 em memória sem senha configurada em dev | Baixa |

---

## 6. Entidades e Relacionamentos

### Diagrama ER simplificado

```
┌──────────┐        ┌─────────────┐        ┌──────────┐
│  users   │ 1    N │  vehicles   │ 1    N │  refuels │
│──────────│────────│─────────────│────────│──────────│
│ id       │        │ id          │        │ id       │
│ email    │        │ type        │        │ odometer │
│ password │        │ energy_type │        │ km_since │
│ name     │        │ fuel_sub_type        │ energy_amt│
│ phone    │        │ current_km  │        │ price_unit│
│ profile  │        │ capacity    │        │ total_amt │
│ created_at         │ brand       │        │ full_tank│
│ updated_at         │ model       │        │ refuel_date│
└──────────┘        │ manufacture_year     │ vehicle_id│
                    │ model_year  │        └──────────┘
                    │ color       │
                    │ license_plate│
                    │ photo       │
                    │ is_active   │
                    │ user_id     │
                    └─────────────┘
```

### User

| Campo | Tipo | Regra |
|---|---|---|
| id | Long | PK, auto-increment |
| email | String | UNIQUE, NOT NULL |
| password | String | NOT NULL, BCrypt hash |
| name | String | opcional |
| phone | String | opcional |
| profile_picture | String | caminho do arquivo |
| created_at | LocalDateTime | auto |
| updated_at | LocalDateTime | auto |

**Relacionamentos:** `@OneToMany(vehicles)` com `CascadeType.ALL`

### Vehicle

| Campo | Tipo | Regra |
|---|---|---|
| id | Long | PK, auto-increment |
| type | String | NOT NULL (ex: "Carro", "Moto") |
| energy_type | EnergyType (enum) | NOT NULL, salvo como ordinal |
| fuel_sub_type | String | opcional (ex: "Gasolina", "Etanol") |
| current_km | Integer | NOT NULL, ≥ 0 |
| capacity | Integer | NOT NULL, ≥ 1 (litros ou kWh do tanque) |
| brand | String | opcional |
| model | String | opcional |
| manufacture_year | Integer | 1886–2100 |
| model_year | Integer | 1886–2100 |
| color | String | opcional |
| license_plate | String | opcional |
| photo | String | opcional |
| is_active | Boolean | default true |
| user_id | Long | FK → users |

**Relacionamentos:** `@ManyToOne(user)`, `@OneToMany(refuels)` com `CascadeType.ALL + orphanRemoval`

### Refuel

| Campo | Tipo | Regra |
|---|---|---|
| id | Long | PK, auto-increment |
| odometer | Integer | NOT NULL, deve ser ≥ último odômetro |
| km_since_last_refuel | Integer | calculado automaticamente |
| energy_amount | BigDecimal | NOT NULL, ≥ 0.01, ≤ capacidade do veículo |
| price_per_unit | BigDecimal | NOT NULL, dentro do range válido |
| total_amount | BigDecimal | calculado via `@PrePersist / @PreUpdate` |
| full_tank | Boolean | default false |
| refuel_date | LocalDateTime | auto via `@CreationTimestamp` |
| vehicle_id | Long | FK → vehicles |

**Relacionamentos:** `@ManyToOne(vehicle)`

### EnergyType (Enum)

| Valor | Código ordinal | Unidade de energia | Unidade de preço | Unidade de consumo |
|---|---|---|---|---|
| COMBUSTION | 0 | litros | R$/litro | km/L |
| ELECTRIC | 1 | kWh | R$/kWh | km/kWh |
| HYBRID | 2 | litros | R$/litro | km/L |

---

## 7. Regras de Negócio

### Usuários

- E-mail deve ser único no sistema
- Senha mínima de 6 caracteres
- Senha armazenada com hash BCrypt
- Atualização de e-mail valida unicidade antes de salvar
- Usuário só pode alterar seus próprios dados

### Veículos

- Um veículo sempre pertence a um usuário
- Apenas um veículo pode estar ativo (`is_active = true`) por usuário — ao ativar um, todos os outros são desativados
- O odômetro só pode crescer: `updateOdometer` rejeita valores menores que o atual
- Ao registrar abastecimento, o odômetro do veículo é atualizado automaticamente
- Todos os endpoints de veículo verificam propriedade (usuário autenticado deve ser dono)

### Abastecimentos

- Odômetro informado deve ser ≥ ao maior odômetro já registrado para o veículo
- `km_since_last_refuel` é calculado automaticamente: `odômetro atual - último odômetro`
- `total_amount` é calculado automaticamente: `energyAmount × pricePerUnit`
- `energyAmount` não pode exceder a capacidade do tanque/bateria do veículo
- Faixa de preço válida:
  - **Combustão/Híbrido:** R$ 0,50 a R$ 15,00 por litro
  - **Elétrico:** R$ 0,10 a R$ 5,00 por kWh
- Apenas o dono do veículo pode criar/editar/deletar abastecimentos
- Filtro por período: aceita `startDate` e `endDate` como query params opcionais

### Dashboard

- Consumo médio é calculado **apenas com abastecimentos de tanque cheio** (`full_tank = true`)
- Requer no mínimo 2 abastecimentos cheios para calcular consumo médio
- Métricas disponíveis: total de abastecimentos, total gasto, total de energia, preço médio, consumo médio, data e odômetro do último abastecimento

---

## 8. Documentação Completa da API

> **Base URL:** `http://localhost:8080`  
> **Autenticação:** `Authorization: Bearer <JWT>` (exceto rotas públicas)

---

### Módulo Auth — `/api/auth`

---

#### `POST /api/auth/register`

Cadastra um novo usuário.

**Autenticação:** Pública

**Request Body:**
```json
{
  "email": "usuario@email.com",
  "password": "minimo6",
  "name": "Felipe Rocha"
}
```

**Response 200 OK:**
```json
{
  "id": 1,
  "email": "usuario@email.com",
  "name": "Felipe Rocha",
  "phone": null,
  "profilePicture": null,
  "createdAt": "2026-05-19T10:00:00",
  "updatedAt": "2026-05-19T10:00:00"
}
```

**Response 400 Bad Request:**
```
"Email já cadastrado"
"Senha deve ter pelo menos 6 caracteres"
```

---

#### `POST /api/auth/login`

Autentica usuário e retorna JWT.

**Autenticação:** Pública

**Request Body:**
```json
{
  "email": "usuario@email.com",
  "password": "minimo6"
}
```

**Response 200 OK:**
```json
{
  "token": "eyJhbGciOiJIUzUxMiJ9..."
}
```

**Response Header:**
```
Authorization: Bearer eyJhbGciOiJIUzUxMiJ9...
```

**Response 401 Unauthorized:**
```
"Email ou senha inválidos"
```

---

#### `GET /api/auth/{userId}/profile`

Busca o perfil de um usuário.

**Autenticação:** JWT obrigatório

**Path Params:** `userId` (Long)

**Response 200 OK:**
```json
{
  "id": 1,
  "email": "usuario@email.com",
  "name": "Felipe Rocha",
  "phone": "11999999999",
  "profilePicture": "profile_pictures/1_foto.jpg",
  "createdAt": "2026-05-19T10:00:00",
  "updatedAt": "2026-05-19T10:00:00"
}
```

**Response 404:** Usuário não encontrado

---

#### `PUT /api/auth/{userId}/profile`

Atualiza nome, telefone ou e-mail do usuário.

**Autenticação:** JWT obrigatório

**Path Params:** `userId` (Long)

**Request Body** (campos opcionais):
```json
{
  "name": "Novo Nome",
  "phone": "11988887777",
  "email": "novo@email.com"
}
```

**Response 200 OK:** Objeto `User` atualizado  
**Response 400:** Novo e-mail já está em uso  
**Response 404:** Usuário não encontrado

---

#### `POST /api/auth/{userId}/upload-profile-picture`

Faz upload da foto de perfil (multipart/form-data).

**Autenticação:** JWT obrigatório

**Path Params:** `userId` (Long)  
**Form Param:** `file` (MultipartFile)

**Response 200 OK:**
```
"Foto atualizada com sucesso"
```

**Response 404:** Usuário não encontrado

> **Observação:** A implementação atual apenas salva o caminho `profile_pictures/{userId}_{filename}` no banco. O arquivo em si não é persistido em disco ou storage externo nesta versão.

---

#### `DELETE /api/auth/{userId}`

Exclui a conta de um usuário.

**Autenticação:** JWT obrigatório

**Path Params:** `userId` (Long)

**Response 200 OK:**
```
"Conta excluída com sucesso"
```

**Response 404:** Usuário não encontrado

---

### Módulo Vehicles — `/api/vehicles`

> Todos os endpoints verificam que o veículo pertence ao usuário autenticado.

---

#### `POST /api/vehicles`

Cria um novo veículo para o usuário autenticado.

**Autenticação:** JWT obrigatório

**Request Body:**
```json
{
  "type": "Carro",
  "energyType": "COMBUSTION",
  "fuelSubType": "Gasolina",
  "currentKm": 45000,
  "capacity": 55,
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
| type | Sim | NotBlank |
| energyType | Sim | NotNull (COMBUSTION, ELECTRIC, HYBRID) |
| currentKm | Sim | ≥ 0 |
| capacity | Sim | ≥ 1 |
| manufactureYear | Não | 1886–2100 |
| modelYear | Não | 1886–2100 |

**Response 200 OK:** Objeto `Vehicle` completo

---

#### `GET /api/vehicles`

Lista todos os veículos do usuário autenticado.

**Autenticação:** JWT obrigatório

**Response 200 OK:**
```json
[
  {
    "id": 1,
    "type": "Carro",
    "energyType": "COMBUSTION",
    "brand": "Volkswagen",
    "model": "Gol",
    "currentKm": 45000,
    "isActive": true,
    ...
  }
]
```

---

#### `GET /api/vehicles/active`

Retorna o veículo ativo do usuário.

**Autenticação:** JWT obrigatório

**Response 200 OK:** Objeto `Vehicle`  
**Response 404:** Nenhum veículo ativo encontrado

---

#### `GET /api/vehicles/{id}`

Busca um veículo específico por ID.

**Autenticação:** JWT obrigatório

**Path Params:** `id` (Long)

**Response 200 OK:** Objeto `Vehicle`  
**Response 403:** Veículo não pertence ao usuário  
**Response 404:** Veículo não encontrado

---

#### `PUT /api/vehicles/{id}`

Atualiza dados de um veículo.

**Autenticação:** JWT obrigatório

**Path Params:** `id` (Long)  
**Request Body:** Mesmo formato do `POST /api/vehicles`

**Response 200 OK:** Objeto `Vehicle` atualizado  
**Response 403:** Veículo não pertence ao usuário  
**Response 404:** Veículo não encontrado

---

#### `PUT /api/vehicles/{id}/odometer`

Atualiza manualmente o odômetro do veículo.

**Autenticação:** JWT obrigatório

**Path Params:** `id` (Long)  
**Query Param:** `currentKm` (Integer)

**Regra:** Novo valor deve ser ≥ ao valor atual.

**Response 200 OK:** Objeto `Vehicle` atualizado  
**Response 400:** Valor menor que o atual  
**Response 403:** Veículo não pertence ao usuário  
**Response 404:** Veículo não encontrado

---

#### `PUT /api/vehicles/{id}/active`

Define um veículo como ativo (desativa todos os outros do usuário).

**Autenticação:** JWT obrigatório

**Path Params:** `id` (Long)

**Response 200 OK:** Vazio (sem body)  
**Response 403:** Veículo não pertence ao usuário  
**Response 404:** Veículo não encontrado

---

#### `DELETE /api/vehicles/{id}`

Exclui um veículo (e todos os seus abastecimentos por cascade).

**Autenticação:** JWT obrigatório

**Path Params:** `id` (Long)

**Response 200 OK:** Vazio (sem body)  
**Response 403:** Veículo não pertence ao usuário  
**Response 404:** Veículo não encontrado

---

### Módulo Refuels — `/api/refuels`

> Todos os endpoints verificam propriedade via cadeia: `refuel → vehicle → user`.

---

#### `POST /api/refuels`

Registra um novo abastecimento.

**Autenticação:** JWT obrigatório

**Request Body:**
```json
{
  "vehicleId": 1,
  "odometer": 45500,
  "energyAmount": 40.5,
  "pricePerUnit": 5.89,
  "fullTank": true
}
```

| Campo | Obrigatório | Validação |
|---|---|---|
| vehicleId | Sim | NotNull |
| odometer | Sim | ≥ 0, ≥ último odômetro registrado |
| energyAmount | Sim | ≥ 0.01, ≤ capacidade do veículo |
| pricePerUnit | Sim | dentro do range válido por tipo |
| fullTank | Não | default false |

**Response 200 OK:**
```json
{
  "id": 10,
  "refuelDate": "2026-05-19T14:30:00",
  "odometer": 45500,
  "kmSinceLastRefuel": 500,
  "energyAmount": 40.50,
  "pricePerUnit": 5.89,
  "totalAmount": 238.55,
  "fullTank": true,
  "vehicle": { ... }
}
```

**Response 400:** Odômetro menor que o anterior, preço fora do range, quantidade maior que capacidade  
**Response 403:** Veículo não pertence ao usuário  
**Response 404:** Veículo não encontrado

---

#### `GET /api/refuels/vehicle/{vehicleId}`

Lista abastecimentos de um veículo, com filtro opcional por período.

**Autenticação:** JWT obrigatório

**Path Params:** `vehicleId` (Long)  
**Query Params (opcionais):**

| Param | Tipo | Formato | Descrição |
|---|---|---|---|
| startDate | LocalDate | `YYYY-MM-DD` | Data inicial |
| endDate | LocalDate | `YYYY-MM-DD` | Data final |

**Response 200 OK:**
```json
[
  {
    "id": 10,
    "refuelDate": "2026-05-19T14:30:00",
    "odometer": 45500,
    "kmSinceLastRefuel": 500,
    "energyAmount": 40.50,
    "pricePerUnit": 5.89,
    "totalAmount": 238.55,
    "fullTank": true
  }
]
```

**Response 403 / 404:** Idem

---

#### `GET /api/refuels/{id}`

Busca um abastecimento específico.

**Autenticação:** JWT obrigatório

**Path Params:** `id` (Long)

**Response 200 OK:** Objeto `Refuel`  
**Response 403 / 404:** Idem

---

#### `PUT /api/refuels/{id}`

Atualiza um abastecimento existente.

**Autenticação:** JWT obrigatório

**Path Params:** `id` (Long)  
**Request Body** (todos os campos opcionais):
```json
{
  "odometer": 45500,
  "energyAmount": 41.0,
  "pricePerUnit": 5.90,
  "fullTank": true
}
```

**Regras de atualização:**
- Odômetro deve ser ≥ ao abastecimento anterior (não ao atual)
- Quantidade não pode exceder capacidade do veículo
- Preço deve estar na faixa válida

**Response 200 OK:** Objeto `Refuel` atualizado  
**Response 400 / 403 / 404:** Idem

---

#### `DELETE /api/refuels/{id}`

Exclui um abastecimento.

**Autenticação:** JWT obrigatório

**Path Params:** `id` (Long)

**Response 200 OK:** Vazio (sem body)  
**Response 403 / 404:** Idem

---

### Módulo Dashboard — `/api/dashboard`

---

#### `GET /api/dashboard/vehicle/{vehicleId}`

Retorna métricas consolidadas de um veículo.

**Autenticação:** JWT obrigatório  

> **Atenção:** Este endpoint **não valida** se o veículo pertence ao usuário autenticado — qualquer usuário autenticado pode consultar o dashboard de qualquer `vehicleId`. Este é um bug de segurança identificado.

**Path Params:** `vehicleId` (Long)

**Response 200 OK:**
```json
{
  "vehicleId": 1,
  "totalRefuels": 15,
  "totalSpent": 3572.45,
  "totalEnergy": 605.00,
  "averagePrice": 5.90,
  "averageConsumption": 12.35,
  "lastRefuelDate": "2026-05-15",
  "lastOdometer": 52000
}
```

| Campo | Tipo | Descrição |
|---|---|---|
| vehicleId | Long | ID do veículo |
| totalRefuels | Long | Total de abastecimentos registrados |
| totalSpent | BigDecimal | Soma de `total_amount` |
| totalEnergy | BigDecimal | Soma de `energy_amount` (litros ou kWh) |
| averagePrice | BigDecimal | Média de `price_per_unit` |
| averageConsumption | Double | km/L ou km/kWh (apenas tanques cheios) |
| lastRefuelDate | LocalDate | Data do último abastecimento |
| lastOdometer | Integer | Odômetro do último abastecimento |

---

### Erros de autenticação (padrão global)

**Response 401 Unauthorized** (token ausente ou inválido):
```json
{
  "error": "Acesso negado",
  "message": "Full authentication is required to access this resource",
  "timestamp": 1747641600000,
  "request": {
    "method": "GET",
    "uri": "/api/vehicles",
    "query": null,
    "remoteAddr": "127.0.0.1",
    "headers": { ... }
  }
}
```

---

## 9. Fluxo Principal da Aplicação

### Fluxo completo: novo usuário → primeiro abastecimento

```
[1] CADASTRO
    POST /api/auth/register
    { email, password, name }
    ← 200 OK: { id, email, name, ... }

[2] LOGIN
    POST /api/auth/login
    { email, password }
    ← 200 OK: { token: "eyJ..." }
    ← Header: Authorization: Bearer eyJ...

[3] CADASTRAR VEÍCULO
    POST /api/vehicles
    Authorization: Bearer eyJ...
    { type: "Carro", energyType: "COMBUSTION", currentKm: 45000, capacity: 55, ... }
    ← 200 OK: { id: 1, ... }

[4] REGISTRAR ABASTECIMENTO
    POST /api/refuels
    Authorization: Bearer eyJ...
    { vehicleId: 1, odometer: 45500, energyAmount: 40.5, pricePerUnit: 5.89, fullTank: true }
    ← 200 OK: { id: 10, kmSinceLastRefuel: 500, totalAmount: 238.55, ... }
    [automático] vehicle.currentKm ← 45500

[5] VER DASHBOARD
    GET /api/dashboard/vehicle/1
    Authorization: Bearer eyJ...
    ← 200 OK: { totalRefuels: 1, totalSpent: 238.55, averageConsumption: 0.0, ... }
    (consumo médio = 0.0 pois precisa de ≥ 2 abastecimentos cheios)

[6] SEGUNDO ABASTECIMENTO
    POST /api/refuels
    { vehicleId: 1, odometer: 46000, energyAmount: 38.0, pricePerUnit: 5.89, fullTank: true }
    ← kmSinceLastRefuel: 500

[7] DASHBOARD COM CONSUMO
    GET /api/dashboard/vehicle/1
    ← averageConsumption: 13.16  (500 km / 38L)
```

### Fluxo de autenticação JWT

```
Requisição chegando
       │
       ▼
JwtAuthenticationFilter.doFilterInternal()
       │
       ├── Sem header "Authorization: Bearer ..." ?
       │       └── Response 401 com JSON detalhado → FIM
       │
       ├── Token extraído → jwtUtil.validateToken()
       │       └── Inválido/expirado → Response 401 → FIM
       │
       ├── jwtUtil.extractEmail(token)
       │
       ├── userRepository.findByEmail(email)
       │       └── Não encontrado → SecurityContext vazio → Spring retorna 403
       │
       ├── Cria UsernamePasswordAuthenticationToken(user, ROLE_USER)
       ├── Seta no SecurityContextHolder
       │
       ▼
Controller.method(@AuthenticationPrincipal User user)
       │ user == objeto da entidade User completo do banco
       ▼
Service verifica propriedade → executa lógica → Response
```

### Fluxo de troca de veículo ativo

```
PUT /api/vehicles/{id}/active
       │
       ▼
VehicleService.setActiveVehicle(user, vehicleId)
       │
       ├── Busca veículo → verifica posse
       │
       ├── Busca TODOS os veículos do usuário
       │
       ├── Para cada veículo:
       │       isActive = (v.id == vehicleId)
       │
       └── Salva todos → apenas 1 ativo por usuário
```

---

## 10. Banco de Dados

### Configuração atual (desenvolvimento)

```properties
# H2 in-memory (dados perdidos ao reiniciar)
spring.datasource.url=jdbc:h2:mem:flowfuel
spring.jpa.hibernate.ddl-auto=create-drop

# Console H2 disponível em:
# http://localhost:8080/h2-console
# JDBC URL: jdbc:h2:mem:flowfuel
# User: sa | Password: (vazio)
```

### Tabelas geradas pelo Hibernate

| Tabela | Descrição |
|---|---|
| `users` | Usuários do sistema |
| `vehicles` | Veículos cadastrados |
| `refuels` | Histórico de abastecimentos |

### Estratégia de persistência

- `ddl-auto=create-drop`: schema recriado a cada inicialização (apenas dev)
- `GenerationType.IDENTITY`: chaves primárias auto-incremento pelo banco
- `CascadeType.ALL` em User→Vehicles e `orphanRemoval=true` em Vehicle→Refuels
- `@PrePersist / @PreUpdate` na entidade Refuel para calcular `totalAmount`
- `@CreationTimestamp / @UpdateTimestamp` para timestamps automáticos

### Para produção (PostgreSQL)

Adicionar ao `application.properties` de produção:
```properties
spring.datasource.url=jdbc:postgresql://HOST:5432/flowfuel
spring.datasource.username=SEU_USUARIO
spring.datasource.password=SUA_SENHA
spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect
spring.jpa.hibernate.ddl-auto=validate
jwt.secret=SUA_CHAVE_SECRETA_FORTE_AQUI
```

---

## 11. Como Rodar o Projeto

### Pré-requisitos

- Java 21+
- Maven (ou usar o wrapper `mvnw` incluído)

### Instalação e execução

```bash
# Clonar o repositório
git clone <url-do-repo>
cd flowfuel

# Compilar e rodar (usa H2 in-memory por padrão)
./mvnw spring-boot:run

# Ou gerar o JAR e executar
./mvnw clean package
java -jar target/flowfuel-0.0.1-SNAPSHOT.jar
```

### Endpoints úteis após inicialização

| URL | Descrição |
|---|---|
| `http://localhost:8080` | Health check / home |
| `http://localhost:8080/swagger-ui/index.html` | Swagger UI (documentação interativa) |
| `http://localhost:8080/v3/api-docs` | OpenAPI JSON |
| `http://localhost:8080/h2-console` | Console H2 (dev only) |

### Rodar testes

```bash
./mvnw test
```

> Os testes usam H2 in-memory (configurado em `src/test/resources/application.properties`).

---

## 12. Qualidade do Código e Melhorias Sugeridas

### Pontos positivos

- Separação clara em módulos por domínio (`user`, `vehicle`, `refuel`, `dashboard`)
- DTOs para entrada/saída — entidades não expostas diretamente (exceto em alguns casos)
- Validações de Bean Validation nos DTOs
- Verificação de propriedade consistente (ownsVehicle, ownsRefuel)
- Logs SQL habilitados em dev (`show-sql=true`)
- Swagger/OpenAPI configurado

### Inconsistências e problemas identificados

| # | Problema | Localização | Impacto |
|---|---|---|---|
| 1 | Dashboard não verifica posse do veículo | `DashboardController` | Segurança — qualquer usuário autenticado acessa dados de qualquer veículo |
| 2 | `@CrossOrigin(origins = "*")` nos controllers | Todos os controllers | Anula a configuração restritiva de CORS do `SecurityConfig` |
| 3 | `UserController` expõe entidade `User` direto no register/getProfile | `UserController` | Vazamento da senha (mesmo hasheada) e campos internos |
| 4 | Upload de foto não persiste arquivo real | `UserService.uploadProfilePicture` | Feature incompleta — apenas salva path no banco |
| 5 | `sendPasswordReset` no `UserService` sem endpoint mapeado | `UserService` | Código morto — método existe mas não há controller mapeado |
| 6 | Chave JWT padrão no código-fonte | `JwtUtil` | Risco de segurança se não configurada via `jwt.secret` |
| 7 | `HomeController` não documentado | `HomeController.java` | Sem contexto do que retorna |

### Melhorias sugeridas

#### Segurança
- [ ] Adicionar verificação de posse no `DashboardController`
- [ ] Remover `@CrossOrigin(origins = "*")` dos controllers e depender apenas do `SecurityConfig`
- [ ] Exigir `jwt.secret` via variável de ambiente — sem valor padrão no código
- [ ] Criar DTO de resposta do usuário sem o campo `password`

#### Arquitetura
- [ ] Migrar de `ResponseEntity<?>` para um handler global com `@ControllerAdvice` + `@ExceptionHandler`
- [ ] Criar UserResponseDTO para não expor a entidade User diretamente
- [ ] Implementar `UserDetails` + `UserDetailsService` no Spring Security em vez de carregar o User manualmente no filtro
- [ ] Separar configuração por perfil: `application-dev.properties`, `application-prod.properties`

#### Funcionalidades
- [ ] Implementar endpoint de recuperação de senha (método existe no service, falta controller + lógica de e-mail)
- [ ] Implementar armazenamento real de foto de perfil (S3, MinIO, ou diretório local com validação)
- [ ] Adicionar paginação nos endpoints de listagem (`/api/refuels/vehicle/{id}`)
- [ ] Endpoint de estatísticas mensais (query `getMonthlySpent` já existe no repository)

#### Qualidade
- [ ] Adicionar testes unitários para `RefuelService` e `VehicleService`
- [ ] Adicionar testes de integração com `@SpringBootTest`
- [ ] Configurar Flyway ou Liquibase para migrations versionadas
- [ ] Adicionar `@Valid` nas rotas de atualização de perfil

---

*Documentação gerada por análise estática completa do código-fonte em 2026-05-19.*
