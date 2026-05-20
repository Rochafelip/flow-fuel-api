# FlowFuel

Aplicação Spring Boot para gerenciar usuários e veículos (cadastro, login, veículos, abastecimentos).

**Resumo rápido**
- **Stack:** Java 21, Spring Boot, Spring Data JPA, Spring Security (filtro JWT), Lombok
- **Build:** Maven

**Como rodar**
- Instalar JDK 21 e Maven.
- Build:

```bash
mvn clean package -DskipTests
```

- Rodar:

```bash
java -jar target/flowfuel-0.0.1-SNAPSHOT.jar
# ou
mvn spring-boot:run
```

**Arquivos importantes**
- Configurações de segurança: [src/main/java/com/devappmobile/flowfuel/config/SecurityConfig.java](src/main/java/com/devappmobile/flowfuel/config/SecurityConfig.java)
- Filtro JWT: [src/main/java/com/devappmobile/flowfuel/config/JwtAuthenticationFilter.java](src/main/java/com/devappmobile/flowfuel/config/JwtAuthenticationFilter.java)
- Serviço de usuário: [src/main/java/com/devappmobile/flowfuel/user/UserService.java](src/main/java/com/devappmobile/flowfuel/user/UserService.java)
- Serviço de veículo: [src/main/java/com/devappmobile/flowfuel/vehicle/VehicleService.java](src/main/java/com/devappmobile/flowfuel/vehicle/VehicleService.java)

**Endpoints principais e exemplos (Postman / curl)**

1) Criar usuário
- POST `/api/auth/register`
- Headers: `Content-Type: application/json`
- Body exemplo:

```json
{
  "email": "usuario@exemplo.com",
  "password": "senha123",
  "name": "Fulano",
  "phone": "11999990000"
}
```

2) Login (obter token)
- POST `/api/auth/login`
- Headers: `Content-Type: application/json`
- Body:

```json
{
  "email": "usuario@exemplo.com",
  "password": "senha123"
}
```

- Nota: a implementação atual retorna um token UUID (ver `UserService`). O filtro de segurança (`JwtAuthenticationFilter`) espera um JWT; recomendo ajustar `UserService.login` para retornar um JWT usando `JwtUtil.generateToken(email)`.

3) Criar veículo (requer autenticação)
- POST `/api/vehicles`
- Headers:
  - `Content-Type: application/json`
  - `Authorization: Bearer <TOKEN>` (ver nota acima)
- Body exemplo:

```json
{
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
}
```

4) Listar veículos do usuário
- GET `/api/vehicles`
- Headers:
  - `Authorization: Bearer <TOKEN>`
  - `userId: <id-do-usuario>` (a implementação atual usa `@RequestHeader Long userId`)

5) Obter veículo ativo
- GET `/api/vehicles/active`
- Headers: `Authorization: Bearer <TOKEN>`, `userId: <id-do-usuario>`

6) Atualizar veículo
- PUT `/api/vehicles/{id}`
- Headers: `Content-Type: application/json`, `Authorization: Bearer <TOKEN>`
- Body: somente campos a alterar (ex.: `{"color": "Preto"}`)

7) Atualizar odômetro
- PUT `/api/vehicles/{id}/odometer?currentKm=13000`
- Headers: `Authorization: Bearer <TOKEN>`

8) Marcar veículo ativo
- PUT `/api/vehicles/{id}/active`
- Headers: `Authorization: Bearer <TOKEN>`, `userId: <id-do-usuario>`

9) Deletar veículo
- DELETE `/api/vehicles/{id}`
- Headers: `Authorization: Bearer <TOKEN>`

**Observações / recomendações**
- A entidade `Vehicle` possui `@ManyToOne ... nullable = false` para `user` — portanto, todo veículo deve estar vinculado a um usuário.
- Melhorias recomendadas:
  - Ajustar `UserService.login` para retornar JWT (usar `JwtUtil.generateToken(email)`) e, no filtro, carregar a entidade `User` e setar como principal.
  - Substituir `userId` em headers por obter `userId` do `Authentication` (mais seguro e menos verboso).
  - Adicionar validações (`@Valid`, `@NotBlank`, `@NotNull`, `@Min`) no DTO `VehicleRequestDTO` e usar `@Valid` no controller.
  - Verificar duplicidade de `licensePlate` por usuário antes de salvar (`existsByLicensePlateAndUserId`).
  - Preferir mapping por nome na enum `EnergyType` (ou `@JsonCreator`) para aceitar strings no JSON em vez de ordinais.

**Locais úteis no código**
- `UserController`: [src/main/java/com/devappmobile/flowfuel/user/UserController.java](src/main/java/com/devappmobile/flowfuel/user/UserController.java)
- `UserService`: [src/main/java/com/devappmobile/flowfuel/user/UserService.java](src/main/java/com/devappmobile/flowfuel/user/UserService.java)
- `VehicleController`: [src/main/java/com/devappmobile/flowfuel/vehicle/VehicleController.java](src/main/java/com/devappmobile/flowfuel/vehicle/VehicleController.java)
- `VehicleService`: [src/main/java/com/devappmobile/flowfuel/vehicle/VehicleService.java](src/main/java/com/devappmobile/flowfuel/vehicle/VehicleService.java)

Se quiser, eu já aplico as melhorias recomendadas (JWT no login, validação do DTO, checagem de placa duplicada e validação de `user == null`).
