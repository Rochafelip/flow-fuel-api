# M1 — OpaqueTokenGenerator + AbstractOpaqueToken Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminar a triplicação de `generatePlaintext()`/`sha256()` nos três services de token e os campos JPA duplicados nas três entidades, extraindo `OpaqueTokenGenerator` (utilitário estático) e `AbstractOpaqueToken` (`@MappedSuperclass` parcial).

**Architecture:** `OpaqueTokenGenerator` é uma classe `final` com métodos estáticos no pacote `common/security` — sem Spring, zero dependências externas. `AbstractOpaqueToken` é um `@MappedSuperclass` que absorve `tokenHash`, `expiresAt`, `createdAt` e `isExpired()`; campos de "consumo" (`revokedAt`/`usedAt`) e métodos de estado domain-specific ficam em cada entidade. Os cleanup jobs permanecem separados; a lacuna de testes é coberta com dois novos `@DataJpaTest`.

**Tech Stack:** Java 21, Spring Boot 3.5.7, Spring Data JPA, Hibernate, H2 (testes), JUnit 5, AssertJ, Lombok, Maven (`./mvnw`).

---

## Mapa de Arquivos

**Criar:**
- `src/main/java/com/devappmobile/flowfuel/common/security/OpaqueTokenGenerator.java`
- `src/main/java/com/devappmobile/flowfuel/common/security/AbstractOpaqueToken.java`
- `src/test/java/com/devappmobile/flowfuel/common/security/OpaqueTokenGeneratorTest.java`
- `src/test/java/com/devappmobile/flowfuel/user/PasswordResetTokenCleanupJobTest.java`
- `src/test/java/com/devappmobile/flowfuel/user/ActivationTokenCleanupJobTest.java`

**Modificar:**
- `src/main/java/com/devappmobile/flowfuel/user/RefreshTokenService.java` — remover `TOKEN_BYTES`, `RNG`, `generatePlaintext()`, `sha256()` e imports; usar `OpaqueTokenGenerator.*`
- `src/main/java/com/devappmobile/flowfuel/user/PasswordResetService.java` — idem
- `src/main/java/com/devappmobile/flowfuel/user/AccountActivationService.java` — idem
- `src/main/java/com/devappmobile/flowfuel/user/RefreshToken.java` — extends `AbstractOpaqueToken`; remover `tokenHash`, `expiresAt`, `createdAt`, `isExpired()`; remover `@AllArgsConstructor`; ajustar construtor manual
- `src/main/java/com/devappmobile/flowfuel/user/PasswordResetToken.java` — idem
- `src/main/java/com/devappmobile/flowfuel/user/ActivationToken.java` — idem

---

## Task 1: OpaqueTokenGenerator

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/common/security/OpaqueTokenGenerator.java`
- Create: `src/test/java/com/devappmobile/flowfuel/common/security/OpaqueTokenGeneratorTest.java`

- [ ] **Step 1: Criar o teste (vai falhar — classe ainda não existe)**

Criar `src/test/java/com/devappmobile/flowfuel/common/security/OpaqueTokenGeneratorTest.java`:

```java
package com.devappmobile.flowfuel.common.security;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class OpaqueTokenGeneratorTest {

    @Test
    void generatePlaintext_retorna43Chars() {
        String token = OpaqueTokenGenerator.generatePlaintext();
        assertThat(token).hasSize(43);
    }

    @Test
    void generatePlaintext_ehBase64UrlSafeSemPadding() {
        String token = OpaqueTokenGenerator.generatePlaintext();
        // Base64 URL-safe sem padding: apenas A-Z, a-z, 0-9, -, _
        assertThat(token).matches("[A-Za-z0-9\\-_]+");
        // Decodifica corretamente para 32 bytes
        byte[] decoded = Base64.getUrlDecoder().decode(token + "="); // padding manual p/ decode
        assertThat(decoded).hasSize(32);
    }

    @Test
    void generatePlaintext_producesUniqueValues() {
        String t1 = OpaqueTokenGenerator.generatePlaintext();
        String t2 = OpaqueTokenGenerator.generatePlaintext();
        assertThat(t1).isNotEqualTo(t2);
    }

    @Test
    void sha256_ehDeterministico() {
        String hash1 = OpaqueTokenGenerator.sha256("entrada");
        String hash2 = OpaqueTokenGenerator.sha256("entrada");
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void sha256_diferenciaEntradas() {
        String h1 = OpaqueTokenGenerator.sha256("abc");
        String h2 = OpaqueTokenGenerator.sha256("xyz");
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void sha256_formatoHex64Chars() {
        String hash = OpaqueTokenGenerator.sha256("qualquer");
        assertThat(hash).hasSize(64).matches("[0-9a-f]+");
    }
}
```

- [ ] **Step 2: Rodar o teste para confirmar falha**

```bash
./mvnw test -Dtest=OpaqueTokenGeneratorTest -q
```

Esperado: `COMPILATION ERROR` — `OpaqueTokenGenerator` não existe.

- [ ] **Step 3: Criar `OpaqueTokenGenerator`**

Criar `src/main/java/com/devappmobile/flowfuel/common/security/OpaqueTokenGenerator.java`:

```java
package com.devappmobile.flowfuel.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

public final class OpaqueTokenGenerator {

    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RNG = new SecureRandom();

    private OpaqueTokenGenerator() {}

    public static String generatePlaintext() {
        byte[] buf = new byte[TOKEN_BYTES];
        RNG.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }
}
```

- [ ] **Step 4: Rodar o teste para confirmar que passa**

```bash
./mvnw test -Dtest=OpaqueTokenGeneratorTest -q
```

Esperado: `BUILD SUCCESS` — 6 testes passando.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/common/security/OpaqueTokenGenerator.java \
        src/test/java/com/devappmobile/flowfuel/common/security/OpaqueTokenGeneratorTest.java
git commit -m "feat(security): add OpaqueTokenGenerator utility"
```

---

## Task 2: Migrar RefreshTokenService

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/user/RefreshTokenService.java`

- [ ] **Step 1: Verificar baseline dos testes existentes**

```bash
./mvnw test -Dtest=RefreshTokenServiceTest -q
```

Esperado: `BUILD SUCCESS`.

- [ ] **Step 2: Atualizar `RefreshTokenService`**

Substituir o conteúdo completo do arquivo por:

```java
package com.devappmobile.flowfuel.user;

import com.devappmobile.flowfuel.common.error.AppException;
import com.devappmobile.flowfuel.common.error.ErrorCode;
import com.devappmobile.flowfuel.common.security.OpaqueTokenGenerator;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private final RefreshTokenRepository refreshTokenRepository;

    @Value("${jwt.refresh-token-ttl-ms:2592000000}")
    private long refreshTokenTtlMs;

    @Transactional
    public String issue(User user) {
        String plaintext = OpaqueTokenGenerator.generatePlaintext();
        RefreshToken token = new RefreshToken(user, OpaqueTokenGenerator.sha256(plaintext),
                LocalDateTime.now().plusNanos(refreshTokenTtlMs * 1_000_000L));
        refreshTokenRepository.save(token);
        return plaintext;
    }

    public record RotationResult(User user, String newRefreshToken) {}

    @Transactional
    public RotationResult rotate(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new AppException(ErrorCode.AUTH_REFRESH_INVALID, "Refresh token ausente");
        }

        RefreshToken existing = refreshTokenRepository.findByTokenHash(OpaqueTokenGenerator.sha256(plaintext))
                .orElseThrow(() -> new AppException(ErrorCode.AUTH_REFRESH_INVALID,
                        "Refresh token desconhecido"));

        if (existing.isRevoked()) {
            log.warn("Re-uso de refresh token detectado, revogando todas as sessoes userId={}",
                    existing.getUser().getId());
            refreshTokenRepository.revokeAllActiveByUserId(existing.getUser().getId(),
                    LocalDateTime.now());
            throw new AppException(ErrorCode.AUTH_REFRESH_REVOKED,
                    "Refresh token revogado — sessoes invalidadas por seguranca");
        }

        if (existing.isExpired()) {
            throw new AppException(ErrorCode.AUTH_REFRESH_EXPIRED, "Refresh token expirado");
        }

        String newPlain = OpaqueTokenGenerator.generatePlaintext();
        RefreshToken replacement = new RefreshToken(existing.getUser(),
                OpaqueTokenGenerator.sha256(newPlain),
                LocalDateTime.now().plusNanos(refreshTokenTtlMs * 1_000_000L));
        refreshTokenRepository.save(replacement);

        existing.setRevokedAt(LocalDateTime.now());
        existing.setReplacedBy(replacement);

        return new RotationResult(existing.getUser(), newPlain);
    }

    @Transactional
    public void revoke(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return;
        }
        refreshTokenRepository.findByTokenHash(OpaqueTokenGenerator.sha256(plaintext)).ifPresent(token -> {
            if (!token.isRevoked()) {
                token.setRevokedAt(LocalDateTime.now());
            }
        });
    }

    @Transactional
    public void revokeAllForUser(Long userId) {
        refreshTokenRepository.revokeAllActiveByUserId(userId, LocalDateTime.now());
    }
}
```

- [ ] **Step 3: Rodar testes de regressão**

```bash
./mvnw test -Dtest="RefreshTokenServiceTest,RefreshTokenCleanupJobTest" -q
```

Esperado: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/user/RefreshTokenService.java
git commit -m "refactor(user): delegate token generation to OpaqueTokenGenerator in RefreshTokenService"
```

---

## Task 3: Migrar PasswordResetService

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/user/PasswordResetService.java`

- [ ] **Step 1: Verificar baseline**

```bash
./mvnw test -Dtest=PasswordResetServiceTest -q
```

Esperado: `BUILD SUCCESS`.

- [ ] **Step 2: Atualizar `PasswordResetService`**

Substituir o conteúdo completo do arquivo por:

```java
package com.devappmobile.flowfuel.user;

import com.devappmobile.flowfuel.common.error.AppException;
import com.devappmobile.flowfuel.common.error.ErrorCode;
import com.devappmobile.flowfuel.common.security.OpaqueTokenGenerator;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final PasswordResetNotifier notifier;

    @Value("${flowfuel.password-reset.token-ttl-minutes:30}")
    private long tokenTtlMinutes;

    @Value("${flowfuel.password-reset.expose-token:false}")
    private boolean exposeToken;

    @Transactional
    public ForgotPasswordResponse requestReset(String email) {
        ForgotPasswordResponse response = ForgotPasswordResponse.standard();

        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            log.info("Solicitacao de reset para email nao cadastrado — ignorando silenciosamente");
            return response;
        }

        User user = userOpt.get();
        LocalDateTime now = LocalDateTime.now();
        tokenRepository.invalidateActiveByUserId(user.getId(), now);

        String plaintext = OpaqueTokenGenerator.generatePlaintext();
        tokenRepository.save(new PasswordResetToken(user, OpaqueTokenGenerator.sha256(plaintext),
                now.plusMinutes(tokenTtlMinutes)));

        notifier.sendResetToken(user, plaintext);

        return exposeToken ? response.withToken(plaintext) : response;
    }

    @Transactional
    public void reset(String plaintext, String newPassword) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new AppException(ErrorCode.AUTH_RESET_INVALID, "Token de redefinição ausente");
        }

        PasswordResetToken token = tokenRepository.findByTokenHash(OpaqueTokenGenerator.sha256(plaintext))
                .filter(PasswordResetToken::isUsable)
                .orElseThrow(() -> new AppException(ErrorCode.AUTH_RESET_INVALID,
                        "Token de redefinição inválido ou expirado"));

        User user = token.getUser();
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        token.setUsedAt(LocalDateTime.now());
        refreshTokenService.revokeAllForUser(user.getId());

        log.info("Senha redefinida via token de reset userId={}", user.getId());
    }
}
```

- [ ] **Step 3: Rodar testes de regressão**

```bash
./mvnw test -Dtest=PasswordResetServiceTest -q
```

Esperado: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/user/PasswordResetService.java
git commit -m "refactor(user): delegate token generation to OpaqueTokenGenerator in PasswordResetService"
```

---

## Task 4: Migrar AccountActivationService

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/user/AccountActivationService.java`

- [ ] **Step 1: Atualizar `AccountActivationService`**

Substituir o conteúdo completo do arquivo por:

```java
package com.devappmobile.flowfuel.user;

import com.devappmobile.flowfuel.common.error.AppException;
import com.devappmobile.flowfuel.common.error.ErrorCode;
import com.devappmobile.flowfuel.common.security.OpaqueTokenGenerator;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AccountActivationService {

    private static final Logger log = LoggerFactory.getLogger(AccountActivationService.class);

    private final UserRepository userRepository;
    private final ActivationTokenRepository tokenRepository;
    private final AccountActivationNotifier notifier;

    @Value("${flowfuel.account-activation.token-ttl-minutes:60}")
    private long tokenTtlMinutes;

    @Value("${flowfuel.account-activation.expose-token:false}")
    private boolean exposeToken;

    @Transactional
    public String sendActivation(User user) {
        LocalDateTime now = LocalDateTime.now();
        tokenRepository.invalidateActiveByUserId(user.getId(), now);

        String plaintext = OpaqueTokenGenerator.generatePlaintext();
        tokenRepository.save(new ActivationToken(user, OpaqueTokenGenerator.sha256(plaintext),
                now.plusMinutes(tokenTtlMinutes)));

        notifier.sendActivationLink(user, plaintext);
        return plaintext;
    }

    @Transactional
    public void activate(String plaintext) {
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
    }

    @Transactional
    public AccountActivationResponse resendActivation(String email) {
        AccountActivationResponse response = AccountActivationResponse.standard();

        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty() || userOpt.get().isActive()) {
            log.info("Reenvio de ativacao ignorado (email inexistente ou conta ja ativa)");
            return response;
        }

        String plaintext = sendActivation(userOpt.get());
        return exposeToken ? response.withToken(plaintext) : response;
    }
}
```

- [ ] **Step 2: Rodar testes de regressão**

```bash
./mvnw test -Dtest="RefreshTokenServiceTest,PasswordResetServiceTest,RefreshTokenCleanupJobTest" -q
```

Esperado: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/user/AccountActivationService.java
git commit -m "refactor(user): delegate token generation to OpaqueTokenGenerator in AccountActivationService"
```

---

## Task 5: Criar AbstractOpaqueToken

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/common/security/AbstractOpaqueToken.java`

- [ ] **Step 1: Criar `AbstractOpaqueToken`**

Criar `src/main/java/com/devappmobile/flowfuel/common/security/AbstractOpaqueToken.java`:

```java
package com.devappmobile.flowfuel.common.security;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@MappedSuperclass
@Getter
@Setter
public abstract class AbstractOpaqueToken {

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected AbstractOpaqueToken() {}

    protected AbstractOpaqueToken(String tokenHash, LocalDateTime expiresAt) {
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }
}
```

- [ ] **Step 2: Confirmar que nada quebrou (classe ainda não usada)**

```bash
./mvnw test -Dtest="RefreshTokenServiceTest,PasswordResetServiceTest,RefreshTokenCleanupJobTest" -q
```

Esperado: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/common/security/AbstractOpaqueToken.java
git commit -m "feat(security): add AbstractOpaqueToken MappedSuperclass"
```

---

## Task 6: Migrar entidade RefreshToken

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/user/RefreshToken.java`

- [ ] **Step 1: Atualizar `RefreshToken`**

Substituir o conteúdo completo do arquivo por:

```java
package com.devappmobile.flowfuel.user;

import com.devappmobile.flowfuel.common.security.AbstractOpaqueToken;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

@Entity(name = "RefreshToken")
@Table(name = "refresh_tokens")
@Getter
@Setter
@NoArgsConstructor
public class RefreshToken extends AbstractOpaqueToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "replaced_by_id")
    private RefreshToken replacedBy;

    public RefreshToken(User user, String tokenHash, LocalDateTime expiresAt) {
        super(tokenHash, expiresAt);
        this.user = user;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isActive() {
        return !isRevoked() && !isExpired();
    }
}
```

- [ ] **Step 2: Rodar testes de regressão**

```bash
./mvnw test -Dtest="RefreshTokenServiceTest,RefreshTokenCleanupJobTest" -q
```

Esperado: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/user/RefreshToken.java
git commit -m "refactor(user): RefreshToken extends AbstractOpaqueToken"
```

---

## Task 7: Migrar entidade PasswordResetToken

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/user/PasswordResetToken.java`

- [ ] **Step 1: Atualizar `PasswordResetToken`**

Substituir o conteúdo completo do arquivo por:

```java
package com.devappmobile.flowfuel.user;

import com.devappmobile.flowfuel.common.security.AbstractOpaqueToken;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

@Entity(name = "PasswordResetToken")
@Table(name = "password_reset_tokens")
@Getter
@Setter
@NoArgsConstructor
public class PasswordResetToken extends AbstractOpaqueToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    public PasswordResetToken(User user, String tokenHash, LocalDateTime expiresAt) {
        super(tokenHash, expiresAt);
        this.user = user;
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public boolean isUsable() {
        return !isUsed() && !isExpired();
    }
}
```

- [ ] **Step 2: Rodar testes de regressão**

```bash
./mvnw test -Dtest=PasswordResetServiceTest -q
```

Esperado: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/user/PasswordResetToken.java
git commit -m "refactor(user): PasswordResetToken extends AbstractOpaqueToken"
```

---

## Task 8: Migrar entidade ActivationToken

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/user/ActivationToken.java`

- [ ] **Step 1: Atualizar `ActivationToken`**

Substituir o conteúdo completo do arquivo por:

```java
package com.devappmobile.flowfuel.user;

import com.devappmobile.flowfuel.common.security.AbstractOpaqueToken;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

@Entity(name = "ActivationToken")
@Table(name = "activation_tokens")
@Getter
@Setter
@NoArgsConstructor
public class ActivationToken extends AbstractOpaqueToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    public ActivationToken(User user, String tokenHash, LocalDateTime expiresAt) {
        super(tokenHash, expiresAt);
        this.user = user;
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public boolean isUsable() {
        return !isUsed() && !isExpired();
    }
}
```

- [ ] **Step 2: Rodar testes de regressão**

```bash
./mvnw test -Dtest="RefreshTokenServiceTest,PasswordResetServiceTest,RefreshTokenCleanupJobTest" -q
```

Esperado: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/user/ActivationToken.java
git commit -m "refactor(user): ActivationToken extends AbstractOpaqueToken"
```

---

## Task 9: Testes de PasswordResetTokenCleanupJob

**Files:**
- Create: `src/test/java/com/devappmobile/flowfuel/user/PasswordResetTokenCleanupJobTest.java`

- [ ] **Step 1: Criar o teste**

Criar `src/test/java/com/devappmobile/flowfuel/user/PasswordResetTokenCleanupJobTest.java`:

```java
package com.devappmobile.flowfuel.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "flowfuel.password-reset.cleanup.enabled=true")
@Import(PasswordResetTokenCleanupJob.class)
class PasswordResetTokenCleanupJobTest {

    @Autowired private UserRepository userRepository;
    @Autowired private PasswordResetTokenRepository tokenRepository;
    @Autowired private PasswordResetTokenCleanupJob cleanupJob;

    private User user;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(cleanupJob, "retentionDays", 7);
        user = userRepository.save(new User("cleanup-pr@test.com", "hash", "Cleanup"));
    }

    @Test
    void run_naoDeletaTokensAtivos() {
        tokenRepository.save(new PasswordResetToken(user, "hash1", LocalDateTime.now().plusHours(1)));

        int deleted = cleanupJob.run();

        assertThat(deleted).isZero();
        assertThat(tokenRepository.findAll()).hasSize(1);
    }

    @Test
    void run_deletaTokensExpiradosHaMaisDeRetencao() {
        tokenRepository.save(new PasswordResetToken(user, "hash2", LocalDateTime.now().minusDays(8)));

        int deleted = cleanupJob.run();

        assertThat(deleted).isOne();
        assertThat(tokenRepository.findAll()).isEmpty();
    }

    @Test
    void run_naoDeletaExpiradosDentroDaJanelaDeRetencao() {
        tokenRepository.save(new PasswordResetToken(user, "hash3", LocalDateTime.now().minusDays(3)));

        int deleted = cleanupJob.run();

        assertThat(deleted).isZero();
        assertThat(tokenRepository.findAll()).hasSize(1);
    }

    @Test
    void run_deletaTokensUsadosHaMaisDeRetencao() {
        PasswordResetToken token = new PasswordResetToken(user, "hash4", LocalDateTime.now().plusHours(1));
        token.setUsedAt(LocalDateTime.now().minusDays(8));
        tokenRepository.save(token);

        int deleted = cleanupJob.run();

        assertThat(deleted).isOne();
        assertThat(tokenRepository.findAll()).isEmpty();
    }
}
```

- [ ] **Step 2: Rodar o teste**

```bash
./mvnw test -Dtest=PasswordResetTokenCleanupJobTest -q
```

Esperado: `BUILD SUCCESS` — 4 testes passando.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/devappmobile/flowfuel/user/PasswordResetTokenCleanupJobTest.java
git commit -m "test(user): add PasswordResetTokenCleanupJobTest"
```

---

## Task 10: Testes de ActivationTokenCleanupJob

**Files:**
- Create: `src/test/java/com/devappmobile/flowfuel/user/ActivationTokenCleanupJobTest.java`

- [ ] **Step 1: Criar o teste**

Criar `src/test/java/com/devappmobile/flowfuel/user/ActivationTokenCleanupJobTest.java`:

```java
package com.devappmobile.flowfuel.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "flowfuel.account-activation.cleanup.enabled=true")
@Import(ActivationTokenCleanupJob.class)
class ActivationTokenCleanupJobTest {

    @Autowired private UserRepository userRepository;
    @Autowired private ActivationTokenRepository tokenRepository;
    @Autowired private ActivationTokenCleanupJob cleanupJob;

    private User user;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(cleanupJob, "retentionDays", 7);
        user = userRepository.save(new User("cleanup-act@test.com", "hash", "Cleanup"));
    }

    @Test
    void run_naoDeletaTokensAtivos() {
        tokenRepository.save(new ActivationToken(user, "hash1", LocalDateTime.now().plusHours(1)));

        int deleted = cleanupJob.run();

        assertThat(deleted).isZero();
        assertThat(tokenRepository.findAll()).hasSize(1);
    }

    @Test
    void run_deletaTokensExpiradosHaMaisDeRetencao() {
        tokenRepository.save(new ActivationToken(user, "hash2", LocalDateTime.now().minusDays(8)));

        int deleted = cleanupJob.run();

        assertThat(deleted).isOne();
        assertThat(tokenRepository.findAll()).isEmpty();
    }

    @Test
    void run_naoDeletaExpiradosDentroDaJanelaDeRetencao() {
        tokenRepository.save(new ActivationToken(user, "hash3", LocalDateTime.now().minusDays(3)));

        int deleted = cleanupJob.run();

        assertThat(deleted).isZero();
        assertThat(tokenRepository.findAll()).hasSize(1);
    }

    @Test
    void run_deletaTokensUsadosHaMaisDeRetencao() {
        ActivationToken token = new ActivationToken(user, "hash4", LocalDateTime.now().plusHours(1));
        token.setUsedAt(LocalDateTime.now().minusDays(8));
        tokenRepository.save(token);

        int deleted = cleanupJob.run();

        assertThat(deleted).isOne();
        assertThat(tokenRepository.findAll()).isEmpty();
    }
}
```

- [ ] **Step 2: Rodar o teste**

```bash
./mvnw test -Dtest=ActivationTokenCleanupJobTest -q
```

Esperado: `BUILD SUCCESS` — 4 testes passando.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/devappmobile/flowfuel/user/ActivationTokenCleanupJobTest.java
git commit -m "test(user): add ActivationTokenCleanupJobTest"
```

---

## Task 11: Suíte completa + atualizar roadmap

**Files:**
- Modify: `docs/roadmap/phase-3/M1-opaque-token-generator.md`

- [ ] **Step 1: Rodar toda a suíte do módulo user**

```bash
./mvnw test -Dtest="OpaqueTokenGeneratorTest,RefreshTokenServiceTest,RefreshTokenCleanupJobTest,PasswordResetServiceTest,PasswordResetTokenCleanupJobTest,ActivationTokenCleanupJobTest" -q
```

Esperado: `BUILD SUCCESS` — todos os testes passando.

- [ ] **Step 2: Rodar build completo**

```bash
./mvnw test -q
```

Esperado: `BUILD SUCCESS`.

- [ ] **Step 3: Atualizar status no roadmap**

No arquivo `docs/roadmap/phase-3/M1-opaque-token-generator.md`, alterar o frontmatter:

```yaml
status: done
```

E atualizar o checklist:

```markdown
- [x] Analisar código atual
- [x] Implementar solução
- [x] Adicionar testes
- [x] Atualizar documentação
- [x] Executar testes de regressão
- [ ] Abrir PR
```

- [ ] **Step 4: Commit final**

```bash
git add docs/roadmap/phase-3/M1-opaque-token-generator.md
git commit -m "chore(roadmap): mark M1 as done"
```
