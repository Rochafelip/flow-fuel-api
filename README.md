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

## Observações

- Todo `Vehicle` é vinculado a um `User` (`@ManyToOne nullable = false`).
- DTOs usam Bean Validation (`@Valid`, `@NotBlank`, etc.).
- `EnergyType` é uma enum — verifique os valores aceitos em [EnergyType.java](src/main/java/com/devappmobile/flowfuel/vehicle/EnergyType.java).
