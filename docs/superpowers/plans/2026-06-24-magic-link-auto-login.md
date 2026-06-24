# Magic Link Auto-Login (Account Activation) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `POST /auth/activate` validates the activation token, marks the account `ACTIVE`, and now also issues a JWT access+refresh token pair in the response — so the user is logged in immediately after clicking the activation link, with no separate login step.

**Architecture:** Extract the existing token-issuing logic out of `AuthService` (where it's currently a private method) into a new standalone `TokenIssuer` component. Both `AuthService` (for normal login) and `AccountActivationService` (for activation auto-login) depend on `TokenIssuer`. This avoids a Spring circular dependency, since `AuthService` already depends on `AccountActivationService` for `register()`.

**Tech Stack:** Spring Boot, Spring Data JPA, JUnit 5 + Mockito + AssertJ, MockMvc for integration tests.

**Reference spec:** `docs/superpowers/specs/2026-06-24-magic-link-auto-login-design.md`

**Note on a spec correction:** the design spec uses the field name `expiresInSeconds` for `TokenPairResponse`. The actual existing record (`src/main/java/com/devappmobile/flowfuel/user/TokenPairResponse.java`) uses `expiresIn`. This plan uses the real field name, `expiresIn`, throughout.

---

## File Structure

```
Create: src/main/java/com/devappmobile/flowfuel/user/TokenIssuer.java
Create: src/test/java/com/devappmobile/flowfuel/user/TokenIssuerTest.java
Create: src/test/java/com/devappmobile/flowfuel/user/AccountActivationServiceTest.java
Modify: src/main/java/com/devappmobile/flowfuel/user/AuthService.java
Modify: src/main/java/com/devappmobile/flowfuel/user/AccountActivationService.java
Modify: src/main/java/com/devappmobile/flowfuel/user/UserController.java
Modify: src/test/java/com/devappmobile/flowfuel/user/AuthServiceTest.java
Modify: src/test/java/com/devappmobile/flowfuel/user/UserControllerIntegrationTest.java
Modify: docs/spec/openapi.yaml
Modify: docs/business-flows/autenticacao.md
Modify: docs/endpoint-flows/autenticacao-e-usuario.md
```

---

### Task 1: Create `TokenIssuer` with passing test

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/user/TokenIssuer.java`
- Test: `src/test/java/com/devappmobile/flowfuel/user/TokenIssuerTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/devappmobile/flowfuel/user/TokenIssuerTest.java`:

```java
package com.devappmobile.flowfuel.user;

import com.devappmobile.flowfuel.config.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenIssuerTest {

    @Mock private JwtUtil jwtUtil;
    @Mock private RefreshTokenService refreshTokenService;

    @InjectMocks private TokenIssuer tokenIssuer;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User("test@example.com", "hashed_password", "Test User");
        user.setId(1L);
    }

    @Test
    void issueTokenPair_retornaAccessTokenERefreshToken() {
        when(jwtUtil.generateToken("test@example.com", 1L)).thenReturn("jwt-token-gerado");
        when(jwtUtil.getAccessTokenTtlMs()).thenReturn(900_000L);
        when(refreshTokenService.issue(user)).thenReturn("refresh-plain");

        TokenPairResponse response = tokenIssuer.issueTokenPair(user);

        assertThat(response.accessToken()).isEqualTo("jwt-token-gerado");
        assertThat(response.refreshToken()).isEqualTo("refresh-plain");
        assertThat(response.expiresIn()).isEqualTo(900L);
    }

    @Test
    void issueTokenPair_usaTtlConfiguradoDoJwtUtil() {
        when(jwtUtil.generateToken("test@example.com", 1L)).thenReturn("jwt-token");
        when(jwtUtil.getAccessTokenTtlMs()).thenReturn(1_800_000L);
        when(refreshTokenService.issue(user)).thenReturn("refresh-plain");

        TokenPairResponse response = tokenIssuer.issueTokenPair(user);

        assertThat(response.expiresIn()).isEqualTo(1800L);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=TokenIssuerTest`
Expected: FAIL — compilation error, `TokenIssuer` class does not exist.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/devappmobile/flowfuel/user/TokenIssuer.java`:

```java
package com.devappmobile.flowfuel.user;

import com.devappmobile.flowfuel.config.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Emite o par access+refresh token para um usuario autenticado.
 * Extraido de AuthService para ser reutilizavel por AccountActivationService
 * sem criar dependencia circular (AuthService -> AccountActivationService).
 */
@Component
@RequiredArgsConstructor
public class TokenIssuer {

    private final JwtUtil jwtUtil;
    private final RefreshTokenService refreshTokenService;

    public TokenPairResponse issueTokenPair(User user) {
        String accessToken = jwtUtil.generateToken(user.getEmail(), user.getId());
        String refreshToken = refreshTokenService.issue(user);
        return new TokenPairResponse(accessToken, refreshToken,
                jwtUtil.getAccessTokenTtlMs() / 1000);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=TokenIssuerTest`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/user/TokenIssuer.java src/test/java/com/devappmobile/flowfuel/user/TokenIssuerTest.java
git commit -m "feat: extract TokenIssuer from AuthService"
```

---

### Task 2: Update `AuthService` to use `TokenIssuer`

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/user/AuthService.java`
- Modify: `src/test/java/com/devappmobile/flowfuel/user/AuthServiceTest.java`

- [ ] **Step 1: Update the failing test first**

In `src/test/java/com/devappmobile/flowfuel/user/AuthServiceTest.java`:

Add the new mock field next to the existing ones (after line 32 `@Mock private AccountActivationService accountActivationService;`):

```java
    @Mock private TokenIssuer tokenIssuer;
```

Replace the `login_comCredenciaisValidas_retornaTokenPair` test (lines 106-120) with:

```java
    @Test
    void login_comCredenciaisValidas_retornaTokenPair() {
        TokenPairResponse expected = new TokenPairResponse("jwt-token-gerado", "refresh-plain", 900L);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("senha123", "hashed_password")).thenReturn(true);
        when(tokenIssuer.issueTokenPair(existingUser)).thenReturn(expected);

        TokenPairResponse response = authService.login("test@example.com", "senha123");

        assertThat(response).isEqualTo(expected);
    }
```

Replace `verifyNoInteractions(refreshTokenService);` in `login_comContaPendente_lancaAccountNotActivated` (line 149) with:

```java
        verifyNoInteractions(tokenIssuer);
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=AuthServiceTest`
Expected: FAIL — compilation error (`TokenIssuer` field has no matching constructor param in `AuthService` yet) or `UnnecessaryStubbingException`/mock not used, since `AuthService` doesn't have a `tokenIssuer` field yet.

- [ ] **Step 3: Modify `AuthService`**

In `src/main/java/com/devappmobile/flowfuel/user/AuthService.java`:

Add the field after line 23 (`private final AccountActivationService accountActivationService;`):

```java
    private final TokenIssuer tokenIssuer;
```

Replace the `login` method body's last line (line 57, `return issueTokenPair(user);`) with:

```java
        return tokenIssuer.issueTokenPair(user);
```

Delete the now-unused private `issueTokenPair` method (lines 98-103):

```java
    private TokenPairResponse issueTokenPair(User user) {
        String accessToken = jwtUtil.generateToken(user.getEmail(), user.getId());
        String refreshToken = refreshTokenService.issue(user);
        return new TokenPairResponse(accessToken, refreshToken,
                jwtUtil.getAccessTokenTtlMs() / 1000);
    }
```

`jwtUtil` and `refreshTokenService` fields stay in `AuthService` — they're still used directly by `refresh()` (line 60-65) and `changePassword()` (line 88).

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=AuthServiceTest`
Expected: PASS (all tests, including the two modified ones)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/user/AuthService.java src/test/java/com/devappmobile/flowfuel/user/AuthServiceTest.java
git commit -m "refactor: AuthService.login delegates to TokenIssuer"
```

---

### Task 3: `AccountActivationService.activate()` returns `TokenPairResponse`

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/user/AccountActivationService.java`
- Create: `src/test/java/com/devappmobile/flowfuel/user/AccountActivationServiceTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/devappmobile/flowfuel/user/AccountActivationServiceTest.java`:

```java
package com.devappmobile.flowfuel.user;

import com.devappmobile.flowfuel.common.error.AppException;
import com.devappmobile.flowfuel.common.error.ErrorCode;
import com.devappmobile.flowfuel.common.security.OpaqueTokenGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountActivationServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private ActivationTokenRepository tokenRepository;
    @Mock private AccountActivationNotifier notifier;
    @Mock private TokenIssuer tokenIssuer;

    @InjectMocks private AccountActivationService accountActivationService;

    private User pendingUser;

    @BeforeEach
    void setUp() {
        pendingUser = new User("pendente@example.com", "hashed", "Pendente");
        pendingUser.setId(5L);
        pendingUser.setStatus(UserStatus.PENDING_ACTIVATION);
    }

    @Test
    void activate_comTokenValido_ativaContaERetornaTokenPair() {
        String plaintext = "plain-token";
        ActivationToken token = new ActivationToken(pendingUser,
                OpaqueTokenGenerator.sha256(plaintext), LocalDateTime.now().plusMinutes(30));
        TokenPairResponse expected = new TokenPairResponse("access", "refresh", 900L);

        when(tokenRepository.findByTokenHash(OpaqueTokenGenerator.sha256(plaintext)))
                .thenReturn(Optional.of(token));
        when(tokenIssuer.issueTokenPair(pendingUser)).thenReturn(expected);

        TokenPairResponse response = accountActivationService.activate(plaintext);

        assertThat(response).isEqualTo(expected);
        assertThat(pendingUser.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(token.isUsed()).isTrue();
        verify(userRepository).save(pendingUser);
    }

    @Test
    void activate_comTokenInexistente_lancaAuthActivationInvalid() {
        when(tokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountActivationService.activate("token-inexistente"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_ACTIVATION_INVALID));
        verifyNoInteractions(tokenIssuer);
    }

    @Test
    void activate_comTokenJaUsado_lancaAuthActivationInvalid() {
        String plaintext = "usado-token";
        ActivationToken token = new ActivationToken(pendingUser,
                OpaqueTokenGenerator.sha256(plaintext), LocalDateTime.now().plusMinutes(30));
        token.setUsedAt(LocalDateTime.now().minusMinutes(1));

        when(tokenRepository.findByTokenHash(OpaqueTokenGenerator.sha256(plaintext)))
                .thenReturn(Optional.of(token));

        assertThatThrownBy(() -> accountActivationService.activate(plaintext))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_ACTIVATION_INVALID));
        verifyNoInteractions(tokenIssuer);
    }

    @Test
    void activate_comTokenExpirado_lancaAuthActivationInvalid() {
        String plaintext = "expirado-token";
        ActivationToken token = new ActivationToken(pendingUser,
                OpaqueTokenGenerator.sha256(plaintext), LocalDateTime.now().minusMinutes(1));

        when(tokenRepository.findByTokenHash(OpaqueTokenGenerator.sha256(plaintext)))
                .thenReturn(Optional.of(token));

        assertThatThrownBy(() -> accountActivationService.activate(plaintext))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_ACTIVATION_INVALID));
        verifyNoInteractions(tokenIssuer);
    }

    @Test
    void activate_comTokenAusente_lancaAuthActivationInvalid() {
        assertThatThrownBy(() -> accountActivationService.activate(""))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_ACTIVATION_INVALID));
        verifyNoInteractions(tokenRepository, tokenIssuer);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=AccountActivationServiceTest`
Expected: FAIL — `accountActivationService.activate(plaintext)` does not compile as returning `TokenPairResponse` (current signature is `void`), and `TokenIssuer` is not a field on `AccountActivationService` yet.

- [ ] **Step 3: Modify `AccountActivationService`**

In `src/main/java/com/devappmobile/flowfuel/user/AccountActivationService.java`:

Add the field after line 24 (`private final AccountActivationNotifier notifier;`):

```java
    private final TokenIssuer tokenIssuer;
```

Replace the `activate` method (lines 45-62) with:

```java
    @Transactional
    public TokenPairResponse activate(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new AppException(ErrorCode.AUTH_ACTIVATION_INVALID, "Token de ativação ausente");
        }

        ActivationToken token = tokenRepository.findByTokenHash(OpaqueTokenGenerator.sha256(plaintext))
                .filter(ActivationToken::isUsable)
                .orElseThrow(() -> new AppException(ErrorCode.AUTH_ACTIVATION_INVALID,
                        "Token de ativação inválido ou expirado"));

        User user = token.getUser();
        user.setStatus(UserStatus.ACTIVE);
        userRepository.save(user);

        token.setUsedAt(LocalDateTime.now());
        log.info("Conta ativada via token userId={}", user.getId());

        return tokenIssuer.issueTokenPair(user);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=AccountActivationServiceTest`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/user/AccountActivationService.java src/test/java/com/devappmobile/flowfuel/user/AccountActivationServiceTest.java
git commit -m "feat: AccountActivationService.activate issues JWT token pair"
```

---

### Task 4: Update `UserController` and integration test

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/user/UserController.java`
- Modify: `src/test/java/com/devappmobile/flowfuel/user/UserControllerIntegrationTest.java`

- [ ] **Step 1: Update the failing integration tests first**

In `src/test/java/com/devappmobile/flowfuel/user/UserControllerIntegrationTest.java`:

Replace the `activate_comTokenValido_ativaContaEPermiteLogin` test (lines 121-140) with:

```java
    @Test
    void activate_comTokenValido_ativaContaERetornaTokenPair() throws Exception {
        registrarSemAtivar("ativar@test.com", "senha123");
        String token = solicitarReenvioAtivacao("ativar@test.com");
        assertThat(token).isNotBlank();

        mockMvc.perform(post("/api/v1/auth/activate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token":"%s"}
                        """.formatted(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresIn").isNumber());
    }
```

Replace the second `mockMvc.perform(post("/api/v1/auth/activate")...)` call inside `activate_tokenReutilizado_retorna401` (lines 157-162, the *first* call which is the successful activation) — change its expectation from `.andExpect(status().isNoContent());` to `.andExpect(status().isOk());`. The second call in that same test (lines 165-170) stays as `.andExpect(status().isUnauthorized());` since it's the reuse attempt.

Add the static import if not already present at the top of the file (check existing imports first — `jsonPath` likely already imported given other tests in the file use JSON body assertions; if not present add):

```java
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=UserControllerIntegrationTest`
Expected: FAIL on `activate_comTokenValido_ativaContaERetornaTokenPair` and `activate_tokenReutilizado_retorna401` — actual status is currently `204`, test expects `200`.

- [ ] **Step 3: Modify `UserController`**

In `src/main/java/com/devappmobile/flowfuel/user/UserController.java`:

Replace the `activate` endpoint (lines 36-40) with:

```java
    @PostMapping("/activate")
    public ResponseEntity<TokenPairResponse> activate(@Valid @RequestBody ActivateAccountRequest request) {
        TokenPairResponse tokens = accountActivationService.activate(request.token());

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + tokens.accessToken());
        return ResponseEntity.ok().headers(headers).body(tokens);
    }
```

(This mirrors the existing `login` endpoint pattern at lines 47-54.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=UserControllerIntegrationTest`
Expected: PASS (all tests)

- [ ] **Step 5: Run the full test suite**

Run: `./mvnw test`
Expected: PASS (no regressions anywhere in the `user` module or elsewhere)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/user/UserController.java src/test/java/com/devappmobile/flowfuel/user/UserControllerIntegrationTest.java
git commit -m "feat: POST /auth/activate returns JWT token pair instead of 204"
```

---

### Task 5: Update documentation

**Files:**
- Modify: `docs/spec/openapi.yaml`
- Modify: `docs/business-flows/autenticacao.md`
- Modify: `docs/endpoint-flows/autenticacao-e-usuario.md`

- [ ] **Step 1: Update `docs/spec/openapi.yaml`**

Find the `/auth/activate` path definition. Replace its `204` response with a `200` response returning the same schema already used by `/auth/login`'s `200` response (the `TokenPairResponse` schema — reuse the existing `$ref` used for login's response, e.g. `#/components/schemas/TokenPairResponse` or whatever the existing login response schema is named in this file). Remove the old `204` response entry. Keep all existing `401`/`400` error responses unchanged.

- [ ] **Step 2: Update `docs/business-flows/autenticacao.md`**

Find the section describing account activation. Update the description of what happens after a successful activation: instead of "conta passa a ACTIVE, usuário ainda precisa fazer login", state that the account becomes `ACTIVE` and the user is immediately authenticated (receives access+refresh tokens in the same response), removing the need for a separate login step. Update any Mermaid sequence diagram for this flow to show the token pair being returned directly from the activation step instead of a separate login step.

- [ ] **Step 3: Update `docs/endpoint-flows/autenticacao-e-usuario.md`**

Find the `POST /auth/activate` endpoint flow description. Update:
- Response: `200 OK` with `TokenPairResponse` body (was `204 No Content`).
- Technical sequence: after `AccountActivationService.activate()` marks the user `ACTIVE` and the token as used, it now also calls `TokenIssuer.issueTokenPair(user)` before returning, which calls `JwtUtil.generateToken()` and `RefreshTokenService.issue()`.
- Note the new component `TokenIssuer` shared with the `/auth/login` flow.

- [ ] **Step 4: Commit**

```bash
git add docs/spec/openapi.yaml docs/business-flows/autenticacao.md docs/endpoint-flows/autenticacao-e-usuario.md
git commit -m "docs: update activation flow docs for auto-login via magic link"
```

---

## Self-Review Notes (for the implementer)

- `TokenPairResponse` field is `expiresIn`, not `expiresInSeconds` — the design spec has a typo; this plan uses the real name throughout. Don't introduce a field rename.
- `AuthService` keeps direct `jwtUtil`/`refreshTokenService` fields — only `login()` moves to `TokenIssuer`. `refresh()` and `changePassword()` are untouched.
- No Flyway migration needed — no schema change in this plan.
- Frontend impact: `POST /auth/activate` response changes from `204` to `200` with a body. This is a breaking API change that must be coordinated with whoever owns the frontend before deploying — flag this when opening the PR.
