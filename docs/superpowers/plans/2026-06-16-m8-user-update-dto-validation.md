# M8 — UserUpdateDTO + @Valid em updateProfile — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Criar `UserUpdateDTO` (record sem `password`) e aplicar `@Valid` em `PUT /auth/{userId}/profile`, garantindo validação de formato de e-mail antes de chegar ao banco.

**Architecture:** O `UserUpdateDTO` é um Java record com `@Email` no campo `email` (permite null, rejeita formato inválido ou string vazia). O controller recebe `@Valid @RequestBody UserUpdateDTO` e o service usa os accessors do record (`email()`, `name()`, `phone()`). A semântica de atualização parcial (null = sem alteração) é preservada.

**Tech Stack:** Spring Boot, Jakarta Bean Validation (`@Valid`, `@Email`), JUnit 5 + MockMvc (integration), Mockito (unit)

---

## File Map

| Ação | Arquivo |
|------|---------|
| **Criar** | `src/main/java/com/devappmobile/flowfuel/user/UserUpdateDTO.java` |
| **Modificar** | `src/main/java/com/devappmobile/flowfuel/user/UserService.java` (linha 136) |
| **Modificar** | `src/main/java/com/devappmobile/flowfuel/user/UserController.java` (linhas 121–127) |
| **Modificar** | `src/test/java/com/devappmobile/flowfuel/user/UserServiceTest.java` |
| **Modificar** | `src/test/java/com/devappmobile/flowfuel/user/UserControllerIntegrationTest.java` |

---

### Task 1: Criar `UserUpdateDTO` record

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/user/UserUpdateDTO.java`

- [ ] **Step 1: Criar o arquivo do record**

```java
package com.devappmobile.flowfuel.user;

import jakarta.validation.constraints.Email;

public record UserUpdateDTO(
        @Email String email,
        String name,
        String phone) {}
```

> **Por que record?** Imutável, sem Lombok, getters viram `email()`, `name()`, `phone()`. `@Email` aceita `null` (campo omitido = sem alteração) e rejeita string vazia ou malformada.

- [ ] **Step 2: Compilar para verificar que o record não tem erros**

```bash
mvn compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/user/UserUpdateDTO.java
git commit -m "feat(user): add UserUpdateDTO record without password field (M8)"
```

---

### Task 2: Escrever testes unitários para `UserService.updateUserProfile` com `UserUpdateDTO`

**Files:**
- Modify: `src/test/java/com/devappmobile/flowfuel/user/UserServiceTest.java`

Estes testes vão **falhar** porque `UserService.updateUserProfile` ainda recebe `UserRegisterDTO`. Isso é esperado — é o passo "Red" do TDD.

- [ ] **Step 1: Adicionar bloco de testes de `updateUserProfile` em `UserServiceTest`**

Adicione os métodos abaixo ao final da classe `UserServiceTest`, antes do último `}`:

```java
// --- updateUserProfile ---

@Test
void updateUserProfile_comNameEPhone_atualizaSemTocarEmail() {
    when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

    UserUpdateDTO dto = new UserUpdateDTO(null, "Novo Nome", "11999990000");
    UserResponseDTO result = userService.updateUserProfile(1L, dto);

    assertThat(result.getName()).isEqualTo("Novo Nome");
    assertThat(result.getEmail()).isEqualTo("test@example.com"); // email original preservado
    verify(userRepository, never()).findByEmail(any());
}

@Test
void updateUserProfile_comEmailNovo_verificaDuplicidadeEAtualiza() {
    when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
    when(userRepository.findByEmail("novo@example.com")).thenReturn(Optional.empty());
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

    UserUpdateDTO dto = new UserUpdateDTO("novo@example.com", null, null);
    UserResponseDTO result = userService.updateUserProfile(1L, dto);

    assertThat(result.getEmail()).isEqualTo("novo@example.com");
    verify(userRepository).findByEmail("novo@example.com");
}

@Test
void updateUserProfile_comEmailDuplicado_lancaConflict() {
    User outro = new User("outro@example.com", "hash", "Outro");
    outro.setId(2L);
    when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
    when(userRepository.findByEmail("outro@example.com")).thenReturn(Optional.of(outro));

    UserUpdateDTO dto = new UserUpdateDTO("outro@example.com", null, null);

    assertThatThrownBy(() -> userService.updateUserProfile(1L, dto))
            .isInstanceOf(ConflictException.class);
    verify(userRepository, never()).save(any());
}

@Test
void updateUserProfile_comTodosCamposNulos_naoAlteraNada() {
    when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

    UserUpdateDTO dto = new UserUpdateDTO(null, null, null);
    UserResponseDTO result = userService.updateUserProfile(1L, dto);

    assertThat(result.getEmail()).isEqualTo("test@example.com");
    assertThat(result.getName()).isEqualTo("Test User");
    verify(userRepository, never()).findByEmail(any());
}

@Test
void updateUserProfile_usuarioInexistente_lancaResourceNotFound() {
    when(userRepository.findById(99L)).thenReturn(Optional.empty());

    UserUpdateDTO dto = new UserUpdateDTO(null, "Nome", null);

    assertThatThrownBy(() -> userService.updateUserProfile(99L, dto))
            .isInstanceOf(ResourceNotFoundException.class);
}
```

- [ ] **Step 2: Rodar os novos testes e confirmar que falham (compilação vai falhar pois a assinatura ainda usa `UserRegisterDTO`)**

```bash
mvn test -pl . -Dtest=UserServiceTest -q 2>&1 | tail -30
```

Expected: falha de compilação porque `userService.updateUserProfile(1L, dto)` recebe `UserUpdateDTO` mas o método ainda aceita `UserRegisterDTO`.

---

### Task 3: Migrar `UserService.updateUserProfile` para `UserUpdateDTO`

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/user/UserService.java` (linha 136)

- [ ] **Step 1: Alterar a assinatura e o corpo do método**

Substitua (linhas 136–150):

```java
public UserResponseDTO updateUserProfile(Long userId, UserRegisterDTO dto) {
    User user = findUserOrThrow(userId);

    if (dto.getName() != null) user.setName(dto.getName());
    if (dto.getPhone() != null) user.setPhone(dto.getPhone());

    if (dto.getEmail() != null && !dto.getEmail().equals(user.getEmail())) {
        if (userRepository.findByEmail(dto.getEmail()).isPresent()) {
            throw new ConflictException(ErrorCode.EMAIL_ALREADY_REGISTERED, "Email já cadastrado");
        }
        user.setEmail(dto.getEmail());
    }

    return UserResponseDTO.from(userRepository.save(user));
}
```

Por:

```java
public UserResponseDTO updateUserProfile(Long userId, UserUpdateDTO dto) {
    User user = findUserOrThrow(userId);

    if (dto.name() != null) user.setName(dto.name());
    if (dto.phone() != null) user.setPhone(dto.phone());

    if (dto.email() != null && !dto.email().equals(user.getEmail())) {
        if (userRepository.findByEmail(dto.email()).isPresent()) {
            throw new ConflictException(ErrorCode.EMAIL_ALREADY_REGISTERED, "Email já cadastrado");
        }
        user.setEmail(dto.email());
    }

    return UserResponseDTO.from(userRepository.save(user));
}
```

> Note: accessors de record não têm prefixo `get` — `dto.name()` não `dto.getName()`.

- [ ] **Step 2: Rodar os testes unitários e confirmar que passam**

```bash
mvn test -pl . -Dtest=UserServiceTest -q 2>&1 | tail -20
```

Expected: `BUILD SUCCESS` com todos os testes de `UserServiceTest` passando.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/user/UserService.java \
        src/test/java/com/devappmobile/flowfuel/user/UserServiceTest.java
git commit -m "feat(user): migrate updateUserProfile to UserUpdateDTO (M8)"
```

---

### Task 4: Adicionar testes de integração para `PUT /auth/{userId}/profile`

**Files:**
- Modify: `src/test/java/com/devappmobile/flowfuel/user/UserControllerIntegrationTest.java`

Estes testes vão **falhar** porque o controller ainda usa `UserRegisterDTO` sem `@Valid`. Isso é esperado.

- [ ] **Step 1: Adicionar método auxiliar de update de perfil e novos testes de integração**

Adicione os testes abaixo na classe `UserControllerIntegrationTest`, antes do último `}`:

```java
// --- updateProfile ---

private long registrarEObterIdEToken(String email, String password, long[] tokenHolder) throws Exception {
    MvcResult reg = registrar(email, password);
    long userId = objectMapper.readTree(reg.getResponse().getContentAsString()).get("id").asLong();
    String token = obterToken(email, password);
    tokenHolder[0] = userId; // reutiliza o array como out-param
    return userId; // retorna userId; token disponível via obterToken já chamado — guarde externamente
}

@Test
void updateProfile_emailMalformado_retorna400ComErroDeValidacao() throws Exception {
    MvcResult reg = registrar("upd1@test.com", "senha123");
    long userId = objectMapper.readTree(reg.getResponse().getContentAsString()).get("id").asLong();
    String token = obterToken("upd1@test.com", "senha123");

    MvcResult result = mockMvc.perform(put("/api/v1/auth/{id}/profile", userId)
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                    {"email":"nao-e-um-email"}
                    """))
            .andExpect(status().isBadRequest())
            .andReturn();

    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    // A resposta de erro de validação deve mencionar o campo "email"
    assertThat(result.getResponse().getContentAsString()).containsIgnoringCase("email");
}

@Test
void updateProfile_emailVazio_retorna400() throws Exception {
    MvcResult reg = registrar("upd2@test.com", "senha123");
    long userId = objectMapper.readTree(reg.getResponse().getContentAsString()).get("id").asLong();
    String token = obterToken("upd2@test.com", "senha123");

    mockMvc.perform(put("/api/v1/auth/{id}/profile", userId)
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                    {"email":""}
                    """))
            .andExpect(status().isBadRequest());
}

@Test
void updateProfile_comPasswordNoBody_eIgnoradoERetorna200() throws Exception {
    MvcResult reg = registrar("upd3@test.com", "senha123");
    long userId = objectMapper.readTree(reg.getResponse().getContentAsString()).get("id").asLong();
    String token = obterToken("upd3@test.com", "senha123");

    // Campo "password" não existe em UserUpdateDTO: Jackson deve ignorá-lo silenciosamente
    MvcResult result = mockMvc.perform(put("/api/v1/auth/{id}/profile", userId)
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                    {"name":"Nome Atualizado","password":"hacker123"}
                    """))
            .andExpect(status().isOk())
            .andReturn();

    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    assertThat(body.get("name").asText()).isEqualTo("Nome Atualizado");
    // Senha não foi alterada: login com a original ainda funciona
    mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                    {"email":"upd3@test.com","password":"senha123"}
                    """))
            .andExpect(status().isOk());
}

@Test
void updateProfile_atualizacaoValidaDeNameEPhone_retorna200() throws Exception {
    MvcResult reg = registrar("upd4@test.com", "senha123");
    long userId = objectMapper.readTree(reg.getResponse().getContentAsString()).get("id").asLong();
    String token = obterToken("upd4@test.com", "senha123");

    MvcResult result = mockMvc.perform(put("/api/v1/auth/{id}/profile", userId)
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                    {"name":"Novo Nome","phone":"11988887777"}
                    """))
            .andExpect(status().isOk())
            .andReturn();

    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    assertThat(body.get("name").asText()).isEqualTo("Novo Nome");
    assertThat(body.get("email").asText()).isEqualTo("upd4@test.com");
}

@Test
void updateProfile_atualizacaoDeEmailValido_retorna200() throws Exception {
    MvcResult reg = registrar("upd5@test.com", "senha123");
    long userId = objectMapper.readTree(reg.getResponse().getContentAsString()).get("id").asLong();
    String token = obterToken("upd5@test.com", "senha123");

    MvcResult result = mockMvc.perform(put("/api/v1/auth/{id}/profile", userId)
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                    {"email":"novoemail@test.com"}
                    """))
            .andExpect(status().isOk())
            .andReturn();

    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    assertThat(body.get("email").asText()).isEqualTo("novoemail@test.com");
}
```

- [ ] **Step 2: Rodar apenas os novos testes e confirmar falha esperada**

```bash
mvn test -pl . -Dtest=UserControllerIntegrationTest#updateProfile_emailMalformado_retorna400ComErroDeValidacao -q 2>&1 | tail -20
```

Expected: `FAILED` — o endpoint retorna 200 em vez de 400 porque ainda falta `@Valid` no controller.

---

### Task 5: Atualizar `UserController.updateProfile` — `@Valid` + `UserUpdateDTO`

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/user/UserController.java` (linhas 121–127)

- [ ] **Step 1: Substituir a assinatura do método `updateProfile`**

Substitua:

```java
@PutMapping("/{userId}/profile")
public UserResponseDTO updateProfile(@PathVariable Long userId,
        @RequestBody UserRegisterDTO userDetails,
        @AuthenticationPrincipal User authUser) {
    ensureSelf(authUser, userId);
    return userService.updateUserProfile(userId, userDetails);
}
```

Por:

```java
@PutMapping("/{userId}/profile")
public UserResponseDTO updateProfile(@PathVariable Long userId,
        @Valid @RequestBody UserUpdateDTO userDetails,
        @AuthenticationPrincipal User authUser) {
    ensureSelf(authUser, userId);
    return userService.updateUserProfile(userId, userDetails);
}
```

> `@Valid` já está importado na classe (linha 4). `UserUpdateDTO` está no mesmo package `com.devappmobile.flowfuel.user` — sem import adicional necessário.

- [ ] **Step 2: Compilar**

```bash
mvn compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Rodar todos os testes**

```bash
mvn test -q 2>&1 | tail -30
```

Expected: `BUILD SUCCESS` com todos os testes passando.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/user/UserController.java \
        src/test/java/com/devappmobile/flowfuel/user/UserControllerIntegrationTest.java
git commit -m "feat(user): apply @Valid + UserUpdateDTO in updateProfile endpoint (M8)"
```

---

### Task 6: Verificar contrato OpenAPI/Swagger

- [ ] **Step 1: Subir a aplicação localmente (perfil dev)**

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev -q &
sleep 15
```

- [ ] **Step 2: Consultar `/v3/api-docs` e confirmar ausência de `password` no schema de `PUT /auth/{userId}/profile`**

```bash
curl -s http://localhost:8080/api/v1/v3/api-docs | \
  python3 -c "
import sys, json
doc = json.load(sys.stdin)
schema_ref = doc['paths']['/auth/{userId}/profile']['put']['requestBody']['content']['application/json']['schema']['\$ref']
schema_name = schema_ref.split('/')[-1]
schema = doc['components']['schemas'][schema_name]
print('Schema name:', schema_name)
print('Fields:', list(schema.get('properties', {}).keys()))
assert 'password' not in schema.get('properties', {}), 'FAIL: password ainda esta no schema!'
print('OK: password nao esta no schema de PUT /auth/{userId}/profile')
"
```

Expected:
```
Schema name: UserUpdateDTO
Fields: ['email', 'name', 'phone']
OK: password nao esta no schema de PUT /auth/{userId}/profile
```

- [ ] **Step 3: Parar a aplicação**

```bash
pkill -f "spring-boot:run" || true
```

- [ ] **Step 4: Commit final (se necessário)**

Se nenhuma alteração adicional foi necessária, apenas marque como concluído. Caso contrário:

```bash
git add -A
git commit -m "chore(user): verify OpenAPI schema for M8 (no password in PUT profile)"
```

---

## Self-Review

### Cobertura do spec

| Requisito | Task |
|-----------|------|
| Criar `UserUpdateDTO` record sem `password` | Task 1 |
| `@Email` opcional (null ok, formato inválido → 400) | Task 1 |
| `updateProfile` com `@Valid @RequestBody UserUpdateDTO` | Task 5 |
| `updateUserProfile` no service aceita `UserUpdateDTO` | Task 3 |
| Semântica de atualização parcial preservada (null = sem alteração) | Task 3 + Task 4 |
| `email` vazio → 400 | Task 4 (teste) + Task 5 |
| `email` malformado → 400 | Task 4 (teste) + Task 5 |
| `password` no body → ignorado silenciosamente | Task 4 (teste) |
| Testes unitários de `updateUserProfile` com `UserUpdateDTO` | Task 2 + Task 3 |
| Testes de integração de `PUT /auth/{userId}/profile` | Task 4 |
| OpenAPI não expõe `password` no endpoint de update | Task 6 |

### Verificação de placeholders

Nenhum placeholder encontrado — todos os passos contêm código completo.

### Consistência de tipos

- `UserUpdateDTO` criado em Task 1: `record` com accessors `email()`, `name()`, `phone()`
- `UserService.updateUserProfile` em Task 3: usa `dto.email()`, `dto.name()`, `dto.phone()` ✓
- `UserController.updateProfile` em Task 5: parâmetro `UserUpdateDTO userDetails` ✓
- Testes em Task 2 e Task 4: constroem `new UserUpdateDTO(email, name, phone)` ✓
