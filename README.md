# FlowFuel

Aplicação Spring Boot para gerenciamento de combustível: cadastro/login de usuários, veículos, abastecimentos e dashboard de consumo.

**Versão atual:** `0.0.1-SNAPSHOT`

## Stack

- **Java:** 21
- **Framework:** Spring Boot 3.5.7 (Web, Data JPA, Security, Validation)
- **Autenticação:** JWT (jjwt 0.11.5) via filtro próprio
- **Banco:** PostgreSQL (runtime) / H2 (testes)
- **Docs:** springdoc-openapi (Swagger UI)
- **Build:** Maven
- **Utilitários:** Lombok

## Como rodar

Pré-requisitos: JDK 21 e Maven.

```bash
# Build
mvn clean package -DskipTests

# Executar
java -jar target/flowfuel-0.0.1-SNAPSHOT.jar
# ou
mvn spring-boot:run
```

## Como testar

```bash
mvn test
```

A suíte cobre testes unitários e de integração dos módulos `user`, `vehicle`, `refuel` e `dashboard`.

## Documentação da API

Com a aplicação em execução, o Swagger UI fica disponível em:

- `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`

## Estrutura do projeto

- [src/main/java/com/devappmobile/flowfuel/config/](src/main/java/com/devappmobile/flowfuel/config/) — `SecurityConfig`, `JwtUtil`, `JwtAuthenticationFilter`
- [src/main/java/com/devappmobile/flowfuel/user/](src/main/java/com/devappmobile/flowfuel/user/) — autenticação e perfil de usuário
- [src/main/java/com/devappmobile/flowfuel/vehicle/](src/main/java/com/devappmobile/flowfuel/vehicle/) — CRUD de veículos
- [src/main/java/com/devappmobile/flowfuel/refuel/](src/main/java/com/devappmobile/flowfuel/refuel/) — registro de abastecimentos
- [src/main/java/com/devappmobile/flowfuel/dashboard/](src/main/java/com/devappmobile/flowfuel/dashboard/) — métricas de consumo por veículo

## Autenticação

1. Faça `POST /api/auth/login` com email e senha.
2. A resposta retorna um JWT no body (`{"token": "..."}`) e também no header `Authorization: Bearer <token>`.
3. Em endpoints protegidos, envie `Authorization: Bearer <token>`. O usuário autenticado é injetado nos controllers via `@AuthenticationPrincipal User user` — não é mais necessário enviar `userId` em headers.

## Endpoints principais

### Autenticação e perfil — `/api/auth`

| Método | Path                                | Descrição                                 |
| ------ | ----------------------------------- | ----------------------------------------- |
| POST   | `/register`                         | Cria usuário                              |
| POST   | `/login`                            | Autentica e retorna JWT                   |
| GET    | `/{userId}/profile`                 | Retorna perfil                            |
| PUT    | `/{userId}/profile`                 | Atualiza perfil                           |
| POST   | `/{userId}/upload-profile-picture`  | Upload de foto (JPEG/PNG/WEBP, máx 5 MB)  |
| DELETE | `/{userId}`                         | Exclui usuário                            |

Exemplo — registrar usuário:

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "usuario@exemplo.com",
    "password": "senha123",
    "name": "Fulano",
    "phone": "11999990000"
  }'
```

Exemplo — login:

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "usuario@exemplo.com", "password": "senha123"}'
```

### Veículos — `/api/vehicles` (requer JWT)

| Método | Path                              | Descrição                              |
| ------ | --------------------------------- | -------------------------------------- |
| POST   | `/`                               | Cria veículo                           |
| GET    | `/`                               | Lista veículos do usuário autenticado  |
| GET    | `/active`                         | Retorna o veículo ativo                |
| GET    | `/{id}`                           | Detalhe do veículo                     |
| PUT    | `/{id}`                           | Atualiza veículo                       |
| PUT    | `/{id}/odometer?currentKm=13000`  | Atualiza odômetro                      |
| PUT    | `/{id}/active`                    | Define veículo ativo                   |
| DELETE | `/{id}`                           | Remove veículo                         |

Exemplo — criar veículo:

```bash
curl -X POST http://localhost:8080/api/vehicles \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <TOKEN>" \
  -d '{
    "type": "Carro",
    "energyType": 0,
    "fuelSubType": "Gasolina",
    "currentKm": 12345,
    "capacity": 50,
    "brand": "Toyota",
    "model": "Corolla",
    "manufactureYear": 2018,
    "modelYear": 2019,
    "color": "Prata",
    "licensePlate": "ABC1D23"
  }'
```

### Abastecimentos — `/api/refuels` (requer JWT)

| Método | Path                     | Descrição                        |
| ------ | ------------------------ | -------------------------------- |
| POST   | `/`                      | Registra abastecimento           |
| GET    | `/vehicle/{vehicleId}`   | Lista abastecimentos do veículo  |
| GET    | `/{id}`                  | Detalhe do abastecimento         |
| PUT    | `/{id}`                  | Atualiza abastecimento           |
| DELETE | `/{id}`                  | Remove abastecimento             |

### Dashboard — `/api/dashboard` (requer JWT)

| Método | Path                     | Descrição                        |
| ------ | ------------------------ | -------------------------------- |
| GET    | `/vehicle/{vehicleId}`   | Métricas de consumo do veículo   |

## Tratamento de erros

A API responde erros no formato [RFC 7807 — Problem Details](https://datatracker.ietf.org/doc/html/rfc7807), com extensões para correlação:

```json
{
  "type": "https://flowfuel.app/errors/VEHICLE_NOT_FOUND",
  "title": "Recurso não encontrado",
  "status": 404,
  "detail": "Veículo não encontrado: 42",
  "instance": "/api/v1/vehicles/42",
  "code": "VEHICLE_NOT_FOUND",
  "requestId": "8f3c9a1e-2b4d-4f7a-9c3e-1a2b3c4d5e6f",
  "timestamp": "2026-05-22T15:30:00-03:00"
}
```

- **`code`** — identificador estável e machine-readable do erro. Use para tratar fluxos no cliente; não traduza a partir do `title`/`detail`.
- **`requestId`** — id único da requisição, também devolvido no header `X-Request-Id`. O mesmo id aparece nos logs do backend e nos eventos do Sentry — peça esse id ao usuário quando reportar problema.
- **`type`** — URI estável apontando para a doc do `code`.

### Catálogo de erros (`ErrorCode`)

Fonte única em [ErrorCode.java](src/main/java/com/devappmobile/flowfuel/common/error/ErrorCode.java). Adicione novos códigos lá quando criar uma nova condição de erro.

| Código                     | HTTP | Quando ocorre                                                                                    |
| -------------------------- | ---- | ------------------------------------------------------------------------------------------------ |
| `VALIDATION_FAILED`        | 400  | Bean Validation falhou (`@Valid`, `@NotBlank`, etc.). O body inclui um array `errors` por campo. |
| `BUSINESS_RULE_VIOLATED`   | 400  | Regra de negócio violada (ex.: odômetro retrocedendo, arquivo acima do tamanho máximo).          |
| `REQUEST_MALFORMED`        | 400  | JSON do body inválido ou ausente.                                                                |
| `AUTH_REQUIRED`            | 401  | Endpoint protegido acessado sem header `Authorization: Bearer ...`.                              |
| `AUTH_BAD_CREDENTIALS`     | 401  | Email/senha inválidos no `POST /api/v1/auth/login`.                                              |
| `AUTH_TOKEN_INVALID`       | 401  | JWT malformado, com assinatura inválida ou expirado.                                             |
| `AUTH_REFRESH_INVALID`     | 401  | Refresh token não encontrado ou desconhecido.                                                    |
| `AUTH_REFRESH_EXPIRED`     | 401  | Refresh token passou da janela de validade.                                                      |
| `AUTH_REFRESH_REVOKED`     | 401  | Refresh token já foi revogado (logout, troca de senha ou detecção de re-uso).                    |
| `FORBIDDEN_OPERATION`      | 403  | Usuário autenticado tentando operar recurso de outro usuário.                                    |
| `RESOURCE_NOT_FOUND`       | 404  | Recurso solicitado não existe (veículo, abastecimento, usuário).                                 |
| `CONFLICT`                 | 409  | Conflito genérico de estado.                                                                     |
| `EMAIL_ALREADY_REGISTERED` | 409  | Tentativa de registro/atualização com email já em uso.                                           |
| `INTERNAL_ERROR`           | 500  | Erro inesperado no servidor. Use o `requestId` para localizar no Sentry.                         |

## Observações

- Todo `Vehicle` é vinculado a um `User` (`@ManyToOne nullable = false`).
- DTOs usam Bean Validation (`@Valid`, `@NotBlank`, etc.).
- `EnergyType` é uma enum — verifique os valores aceitos em [EnergyType.java](src/main/java/com/devappmobile/flowfuel/vehicle/EnergyType.java).
