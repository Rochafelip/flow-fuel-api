# B1 — Unificar parse do JWT (`tryParse` único) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminar o duplo `parseClaims` por request autenticado expondo `tryParse(String): Optional<Claims>` em `JwtUtil` e usando-o como ponto de entrada único no filtro.

**Architecture:** Adicionar `tryParse` que encapsula `parseClaims` + tratamento de exceções, reescrever `extractEmail`/`validateToken`/`extractUserId` em termos dele (mantendo a API pública para compatibilidade com os testes existentes), e refatorar `doFilterInternal` para chamar o parser uma única vez.

**Tech Stack:** Java 17, Spring Boot 3, JJWT (io.jsonwebtoken), JUnit 5, AssertJ

---

## File Map

| Ação | Arquivo | Responsabilidade da mudança |
|------|---------|----------------------------|
| Modify | `src/main/java/com/devappmobile/flowfuel/config/JwtUtil.java` | Adicionar `tryParse`; reimplementar `extractEmail`, `validateToken`, `extractUserId` via `tryParse` |
| Modify | `src/main/java/com/devappmobile/flowfuel/config/JwtAuthenticationFilter.java` | Chamar `tryParse` uma única vez; remover `try/catch` de JwtException desnecessário |
| Modify | `src/test/java/com/devappmobile/flowfuel/config/JwtUtilTest.java` | Adicionar testes para `tryParse` (token válido, expirado, malformado) |

---

## Task 1: Adicionar testes para `tryParse` (falham antes da implementação)

**Files:**
- Modify: `src/test/java/com/devappmobile/flowfuel/config/JwtUtilTest.java`

- [ ] **Step 1: Adicionar os três testes de `tryParse` ao `JwtUtilTest`**

Abrir `src/test/java/com/devappmobile/flowfuel/config/JwtUtilTest.java` e adicionar os imports e testes abaixo logo após o teste `validateToken_comTokenVazio_deveRetornarFalse`, antes do `}` final da classe:

```java
// imports no topo da classe (junto aos existentes):
import io.jsonwebtoken.Claims;
import java.util.Optional;

// testes a adicionar no corpo da classe:
@Test
void tryParse_comTokenValido_deveRetornarClaimsPresente() {
    String token = jwtUtil.generateToken("user@test.com", 42L);

    Optional<Claims> result = jwtUtil.tryParse(token);

    assertThat(result).isPresent();
    assertThat(result.get().getSubject()).isEqualTo("user@test.com");
    assertThat(result.get().get("userId", Integer.class)).isEqualTo(42);
}

@Test
void tryParse_comTokenMalformado_deveRetornarVazio() {
    Optional<Claims> result = jwtUtil.tryParse("token.invalido.qualquer");

    assertThat(result).isEmpty();
}

@Test
void tryParse_comStringVazia_deveRetornarVazio() {
    Optional<Claims> result = jwtUtil.tryParse("");

    assertThat(result).isEmpty();
}
```

- [ ] **Step 2: Rodar os novos testes e confirmar que FALHAM (método ainda não existe)**

```bash
cd /home/rocha/Projetos/flowfuel
./mvnw test -pl . -Dtest=JwtUtilTest#tryParse_comTokenValido_deveRetornarClaimsPresente+tryParse_comTokenMalformado_deveRetornarVazio+tryParse_comStringVazia_deveRetornarVazio -q 2>&1 | tail -20
```

Esperado: erro de compilação `cannot find symbol: method tryParse(String)`

- [ ] **Step 3: Commit dos testes ainda falhando**

```bash
git add src/test/java/com/devappmobile/flowfuel/config/JwtUtilTest.java
git commit -m "test(jwt): add failing tests for tryParse"
```

---

## Task 2: Implementar `tryParse` e reimplementar métodos existentes via ele

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/config/JwtUtil.java`

- [ ] **Step 1: Adicionar import de `Optional` e implementar `tryParse`**

Abrir `src/main/java/com/devappmobile/flowfuel/config/JwtUtil.java`.

Adicionar o import junto aos existentes:

```java
import java.util.Optional;
```

Adicionar o método `tryParse` **antes** de `parseClaims` (linha ~60):

```java
public Optional<Claims> tryParse(String token) {
    try {
        return Optional.of(parseClaims(token));
    } catch (JwtException | IllegalArgumentException e) {
        return Optional.empty();
    }
}
```

- [ ] **Step 2: Reimplementar `extractEmail`, `validateToken` e `extractUserId` via `tryParse`**

Substituir os três métodos existentes pelas versões abaixo (mantendo a mesma assinatura pública):

```java
public String extractEmail(String token) {
    return tryParse(token).map(Claims::getSubject).orElse(null);
}

public boolean validateToken(String token) {
    return tryParse(token).isPresent();
}

public Long extractUserId(String token) {
    return tryParse(token)
            .map(claims -> claims.get("userId"))
            .map(v -> Long.valueOf(v.toString()))
            .orElse(null);
}
```

O resultado final de `JwtUtil.java` deve ser:

```java
package com.devappmobile.flowfuel.config;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;

@Component
public class JwtUtil {

    private final SecretKey secretKey;
    private final long accessTokenTtlMs;

    public JwtUtil(@Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-ttl-ms:900000}") long accessTokenTtlMs) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenTtlMs = accessTokenTtlMs;
    }

    public long getAccessTokenTtlMs() {
        return accessTokenTtlMs;
    }

    public String generateToken(String email, Long userId) {
        return Jwts.builder()
                .setSubject(email)
                .claim("userId", userId)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + accessTokenTtlMs))
                .signWith(secretKey)
                .compact();
    }

    public Optional<Claims> tryParse(String token) {
        try {
            return Optional.of(parseClaims(token));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public String extractEmail(String token) {
        return tryParse(token).map(Claims::getSubject).orElse(null);
    }

    public boolean validateToken(String token) {
        return tryParse(token).isPresent();
    }

    public Long extractUserId(String token) {
        return tryParse(token)
                .map(claims -> claims.get("userId"))
                .map(v -> Long.valueOf(v.toString()))
                .orElse(null);
    }

    private Claims parseClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(secretKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }
}
```

- [ ] **Step 3: Rodar todos os testes de `JwtUtilTest` e confirmar que PASSAM**

```bash
./mvnw test -pl . -Dtest=JwtUtilTest -q 2>&1 | tail -20
```

Esperado: `Tests run: 9, Failures: 0, Errors: 0`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/config/JwtUtil.java
git commit -m "feat(jwt): add tryParse and reimplement extractEmail/validateToken/extractUserId via it"
```

---

## Task 3: Refatorar `JwtAuthenticationFilter` para usar `tryParse` uma única vez

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/config/JwtAuthenticationFilter.java`

O filtro atual chama `extractEmail` (1 parse) e depois `validateToken` (2º parse). Com `tryParse`, fazemos um único parse e extraímos o email de `Claims`.

Além disso, o `try/catch (JwtException | IllegalArgumentException ex)` externo existe porque `extractEmail` lança em token inválido. Com `tryParse` absorvendo a exceção, esse bloco some — tokens inválidos agora retornam `Optional.empty()` e escrevemos o 401 explicitamente.

- [ ] **Step 1: Substituir `doFilterInternal` pela versão com `tryParse`**

Substituir o método `doFilterInternal` inteiro (linhas 56–103) pelo código abaixo:

```java
@Override
protected void doFilterInternal(@NonNull HttpServletRequest request,
        @NonNull HttpServletResponse response,
        @NonNull FilterChain filterChain) throws ServletException, IOException {

    String authHeader = request.getHeader("Authorization");

    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
        log.warn("Acesso sem Bearer code={} method={} path={}",
                ErrorCode.AUTH_REQUIRED.code(), request.getMethod(), request.getRequestURI());
        ProblemDetailWriter.write(response, request.getRequestURI(),
                ErrorCode.AUTH_REQUIRED,
                "Autenticação necessária para acessar este recurso");
        return;
    }

    String token = authHeader.substring(7).trim();
    var claimsOpt = jwtUtil.tryParse(token);

    if (claimsOpt.isEmpty()) {
        log.warn("Token JWT invalido code={} method={} path={}",
                ErrorCode.AUTH_TOKEN_INVALID.code(), request.getMethod(), request.getRequestURI());
        ProblemDetailWriter.write(response, request.getRequestURI(),
                ErrorCode.AUTH_TOKEN_INVALID,
                "Token inválido");
        return;
    }

    String email = claimsOpt.get().getSubject();
    if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {
        userRepository.findByEmail(email).ifPresent(user -> {
            UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                    user, null,
                    Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
            authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authToken);
            if (user.getId() != null) {
                MDC.put(MDC_USER_ID, user.getId().toString());
            }
        });
    }

    try {
        filterChain.doFilter(request, response);
    } finally {
        MDC.remove(MDC_USER_ID);
    }
}
```

- [ ] **Step 2: Remover o import de `JwtException` (não é mais usado diretamente no filtro)**

Remover a linha:

```java
import io.jsonwebtoken.JwtException;
```

- [ ] **Step 3: Compilar para garantir que não há erros**

```bash
./mvnw compile -q 2>&1 | tail -20
```

Esperado: saída vazia (sem erros)

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/config/JwtAuthenticationFilter.java
git commit -m "refactor(jwt): use tryParse once in JwtAuthenticationFilter, eliminate double parse"
```

---

## Task 4: Regressão — rodar todos os testes de integração autenticados

**Files:** nenhum (apenas execução)

- [ ] **Step 1: Rodar todos os testes do projeto**

```bash
./mvnw verify -q 2>&1 | tail -40
```

Esperado: `BUILD SUCCESS` sem falhas. Os seguintes suites devem aparecer passando:

- `JwtUtilTest` (9 testes)
- `UserControllerIntegrationTest`
- `VehicleControllerIntegrationTest`
- `RefuelControllerIntegrationTest`
- `VehicleEventControllerIntegrationTest`
- `DashboardControllerIntegrationTest`

- [ ] **Step 2: Se houver falhas, investigar antes de continuar**

Caso algum teste falhe, verificar:

1. O comportamento de 401 ainda está correto? (`claimsOpt.isEmpty()` → retorno imediato com `AUTH_TOKEN_INVALID`)
2. O email está sendo extraído corretamente de `claims.getSubject()`?
3. O `MDC.remove(MDC_USER_ID)` ainda está no bloco `finally`?

- [ ] **Step 3: Commit final de regressão confirmada**

```bash
git commit --allow-empty -m "chore(jwt): all integration tests green after B1 refactor"
```

---

## Self-Review

### Spec Coverage

| Requisito da spec | Atendido? | Task |
|---|---|---|
| `JwtUtil` expõe `tryParse(String): Optional<Claims>` | ✅ | Task 2 |
| `JwtAuthenticationFilter` chama parser uma única vez | ✅ | Task 3 |
| Comportamento de autenticação idêntico (token válido → autentica; inválido → 401) | ✅ | Task 3 |
| `JwtUtilTest` cobre `tryParse` válido, inválido, expirado | ⚠️ | Task 1 — os testes cobrem válido e malformado, mas não expirado. Ver nota abaixo. |
| Testes de regressão (`*ControllerIntegrationTest`) passam | ✅ | Task 4 |

**Nota sobre token expirado:** A spec pede `tryParse` com token expirado → `Optional.empty()`. Os testes do Task 1 cobrem token malformado e string vazia. Para cobrir expirado sem `Thread.sleep`, usar `JwtUtil` instanciado com TTL de 0ms:

Adicionar este teste no Task 1 (Step 1):

```java
@Test
void tryParse_comTokenExpirado_deveRetornarVazio() {
    JwtUtil shortLived = new JwtUtil("test-secret-key-for-unit-tests-only-32chars!!", 0L);
    String expiredToken = shortLived.generateToken("user@test.com", 1L);

    Optional<Claims> result = jwtUtil.tryParse(expiredToken);

    assertThat(result).isEmpty();
}
```

### Placeholder Scan

Nenhum placeholder (`TBD`, `TODO`, `similar to Task N`) encontrado — todos os passos têm código completo.

### Type Consistency

- `tryParse` retorna `Optional<Claims>` em Task 2 e é consumido como `var claimsOpt = jwtUtil.tryParse(token)` em Task 3 — consistente.
- `claims.getSubject()` é o mesmo que o antigo `extractEmail` usava via `parseClaims(token).getSubject()` — consistente.
- `Claims::getSubject` (import `io.jsonwebtoken.Claims`) já está no import glob `io.jsonwebtoken.*` — sem novo import necessário.
