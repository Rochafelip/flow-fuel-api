# B5 — LoginRequest como record + @Valid — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Converter `UserController.LoginRequest` de classe estática com getters/setters para `record` com anotações de validação, e adicionar `@Valid` no endpoint de login para rejeitar payloads malformados com HTTP 400.

**Architecture:** Mudança isolada em `UserController.java`: substituir a classe interna `LoginRequest` por um `record` com constraints de Bean Validation, adicionar `@Valid` no parâmetro do método `login`, e atualizar os acessores de `getEmail()`/`getPassword()` para `email()`/`password()`. Novos testes de integração cobrem os cenários de validação recém-habilitados.

**Tech Stack:** Java 21 records, Jakarta Bean Validation (`@NotBlank`, `@Email`), Spring Boot `@Valid`, MockMvc (testes de integração).

---

## Mapeamento de Arquivos

| Ação | Arquivo |
|------|---------|
| Modificar | `src/main/java/com/devappmobile/flowfuel/user/UserController.java` |
| Modificar | `src/test/java/com/devappmobile/flowfuel/user/UserControllerIntegrationTest.java` |

---

### Task 1: Escrever os testes de validação que devem falhar

Os testes cobrem os dois cenários novos habilitados por `@Valid`: email malformado e password vazio. Eles devem falhar agora porque `@Valid` ainda não está no endpoint.

**Files:**
- Modify: `src/test/java/com/devappmobile/flowfuel/user/UserControllerIntegrationTest.java`

- [ ] **Step 1: Adicionar os dois novos testes ao final da classe, antes do `}`**

Inserir após o teste `login_comSenhaErrada_retorna401` (linha 421), antes do próximo bloco `// ---`:

```java
@Test
void login_comEmailMalformado_retorna400() throws Exception {
    mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                    {"email":"nao-e-um-email","password":"senha123"}
                    """))
            .andExpect(status().isBadRequest());
}

@Test
void login_comPasswordVazio_retorna400() throws Exception {
    mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                    {"email":"valido@test.com","password":""}
                    """))
            .andExpect(status().isBadRequest());
}
```

- [ ] **Step 2: Verificar que os testes novos falham (comportamento esperado antes da implementação)**

```bash
./mvnw test -Dtest=UserControllerIntegrationTest#login_comEmailMalformado_retorna400+login_comPasswordVazio_retorna400 -q 2>&1 | tail -20
```

Esperado: os dois testes falham com algo como `expected: <400> but was: <200>` (ou `<401>`, dependendo do payload).

---

### Task 2: Implementar a conversão de LoginRequest para record + @Valid

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/user/UserController.java:1-9` (imports)
- Modify: `src/main/java/com/devappmobile/flowfuel/user/UserController.java:44-51` (método login)
- Modify: `src/main/java/com/devappmobile/flowfuel/user/UserController.java:140-159` (classe LoginRequest)

- [ ] **Step 3: Adicionar os imports de validação em `UserController.java`**

Após a linha `import jakarta.validation.Valid;` (linha 4), adicionar:

```java
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
```

O bloco de imports ficará:

```java
import com.devappmobile.flowfuel.exception.ForbiddenOperationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
```

- [ ] **Step 4: Adicionar `@Valid` no parâmetro do método `login` (linha 45)**

Substituir:

```java
    @PostMapping("/login")
    public ResponseEntity<TokenPairResponse> login(@RequestBody LoginRequest loginRequest) {
        TokenPairResponse tokens = userService.login(loginRequest.getEmail(), loginRequest.getPassword());
```

Por:

```java
    @PostMapping("/login")
    public ResponseEntity<TokenPairResponse> login(@Valid @RequestBody LoginRequest loginRequest) {
        TokenPairResponse tokens = userService.login(loginRequest.email(), loginRequest.password());
```

- [ ] **Step 5: Substituir a classe `LoginRequest` pelo record (linhas 140–159)**

Substituir todo o bloco:

```java
    public static class LoginRequest {
        private String email;
        private String password;

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
```

Por:

```java
    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {}
```

- [ ] **Step 6: Verificar que o projeto compila**

```bash
./mvnw compile -q 2>&1 | tail -10
```

Esperado: `BUILD SUCCESS` (sem erros de compilação).

---

### Task 3: Rodar todos os testes e confirmar que passam

- [ ] **Step 7: Rodar toda a suíte de integração de UserController**

```bash
./mvnw test -Dtest=UserControllerIntegrationTest -q 2>&1 | tail -30
```

Esperado: todos os testes passam — incluindo os dois novos (`login_comEmailMalformado_retorna400` e `login_comPasswordVazio_retorna400`) e todos os existentes (login válido continua retornando 200, senha errada retorna 401, etc.).

- [ ] **Step 8: Fazer commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/user/UserController.java \
        src/test/java/com/devappmobile/flowfuel/user/UserControllerIntegrationTest.java
git commit -m "feat(auth): convert LoginRequest to record and add @Valid on login endpoint"
```

---

## Self-Review

### Cobertura do spec

| Requisito | Task |
|-----------|------|
| `LoginRequest` vira `record` com `@NotBlank @Email` em `email` | Task 2 / Step 5 |
| `@NotBlank` em `password` | Task 2 / Step 5 |
| `@Valid` no parâmetro do endpoint de login | Task 2 / Step 4 |
| Acessores atualizados para `email()` / `password()` | Task 2 / Step 4 |
| `POST /auth/login` com email malformado → 400 | Task 1 + Task 3 |
| `POST /auth/login` com password vazio → 400 | Task 1 + Task 3 |
| Login válido continua funcionando (200) | Task 3 / Step 7 |

### Placeholder scan

Nenhum TBD, TODO ou referência incompleta.

### Type consistency

O record define `email()` e `password()` (Task 2/Step 5). O método `login` usa `loginRequest.email()` e `loginRequest.password()` (Task 2/Step 4). Consistente.
