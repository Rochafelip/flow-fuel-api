# Design: M1 — OpaqueTokenGenerator + AbstractOpaqueToken

**Data:** 2026-06-16
**Milestone:** M1 (Phase 3)
**Status:** aprovado

## Contexto

`generatePlaintext()` e `sha256()` são cópias literais em três classes: `RefreshTokenService`, `PasswordResetService`, `AccountActivationService`. As três entidades de token (`RefreshToken`, `PasswordResetToken`, `ActivationToken`) compartilham `tokenHash`, `expiresAt`, `createdAt` e `isExpired()`. Os dois cleanup jobs de password-reset e activation não têm testes.

## Decisões de Design

### 1. `OpaqueTokenGenerator` — classe utilitária estática

Pacote: `com.devappmobile.flowfuel.common.security`

Classe `final` com construtor privado e dois métodos estáticos extraídos literalmente dos 3 services:

- `generatePlaintext()` — 32 bytes aleatórios (`SecureRandom`), codificados em Base64 URL-safe sem padding.
- `sha256(String input)` — SHA-256 do input em UTF-8, retornado como hex lowercase de 64 chars.

**Rationale:** zero acoplamento com Spring; testável com JUnit puro; comportamento idêntico ao atual (sem mudança de formato/algoritmo).

**Alternativa descartada:** `@Component` injetável — adicionaria dependência de container sem benefício prático, já que os testes dos services não mocam geração de token.

### 2. `AbstractOpaqueToken` — `@MappedSuperclass` parcial

Pacote: `com.devappmobile.flowfuel.common.security`

Absorve apenas os campos e comportamentos verdadeiramente idênticos entre as 3 entidades:

| Membro | Tipo | Coluna |
|---|---|---|
| `tokenHash` | `String` | `token_hash` (NOT NULL, UNIQUE, length=64) |
| `expiresAt` | `LocalDateTime` | `expires_at` (NOT NULL) |
| `createdAt` | `LocalDateTime` | `created_at` (NOT NULL, updatable=false, `@CreationTimestamp`) |
| `isExpired()` | método | — |

**O que permanece em cada entidade:**

- `RefreshToken`: `revokedAt`, `replacedBy` (FK self-referencing), `isRevoked()`, `isActive()`.
- `PasswordResetToken`: `usedAt`, `isUsed()`, `isUsable()`.
- `ActivationToken`: `usedAt`, `isUsed()`, `isUsable()`.

**Impacto JPA/Flyway:** zero. Os nomes de coluna são idênticos nas 3 tabelas; `@MappedSuperclass` herda mapeamentos sem criar nova tabela. Nenhuma migration necessária.

**Alternativa descartada:** superclasse completa com campo genérico `consumedAt` — exigiria `@AttributeOverride` em `RefreshToken` e tornaria o modelo semânticamente confuso (`revokedAt` ≠ `usedAt` em intenção).

### 3. Cleanup jobs — sem unificação, apenas testes

Os 3 jobs permanecem separados. `RefreshTokenCleanupJob` tem um passo extra (`clearReplacedByOnOldTokens`) que não existe nos outros, tornando a herança forçada. A lacuna de teste é coberta com dois novos arquivos.

**Alternativa descartada:** `AbstractTokenCleanupJob` com template method — adicionaria abstração onde há um único caso divergente, violando YAGNI.

## Arquivos Novos

```
src/main/java/com/devappmobile/flowfuel/common/security/OpaqueTokenGenerator.java
src/main/java/com/devappmobile/flowfuel/common/security/AbstractOpaqueToken.java
src/test/java/com/devappmobile/flowfuel/common/security/OpaqueTokenGeneratorTest.java
src/test/java/com/devappmobile/flowfuel/user/PasswordResetTokenCleanupJobTest.java
src/test/java/com/devappmobile/flowfuel/user/ActivationTokenCleanupJobTest.java
```

## Arquivos Modificados

```
src/main/java/com/devappmobile/flowfuel/user/RefreshToken.java
src/main/java/com/devappmobile/flowfuel/user/PasswordResetToken.java
src/main/java/com/devappmobile/flowfuel/user/ActivationToken.java
src/main/java/com/devappmobile/flowfuel/user/RefreshTokenService.java
src/main/java/com/devappmobile/flowfuel/user/PasswordResetService.java
src/main/java/com/devappmobile/flowfuel/user/AccountActivationService.java
```

## Testes

### `OpaqueTokenGeneratorTest` (JUnit puro, sem Spring)
1. `generatePlaintext_retorna43Chars` — Base64 URL-safe sem padding de 32 bytes = 43 chars.
2. `generatePlaintext_producesUniqueValues` — duas chamadas produzem strings diferentes.
3. `sha256_ehDeterministico` — mesma entrada → mesmo hash.
4. `sha256_diferenciaEntradas` — inputs diferentes → hashes diferentes.
5. `sha256_formatoHex64Chars` — SHA-256 em hex = 64 caracteres.

### `PasswordResetTokenCleanupJobTest` (`@DataJpaTest`)
1. `run_naoDeletaTokensAtivos` — token válido não é removido.
2. `run_deletaTokensExpiradosHaMaisDeRetencao` — `expiresAt` há >7 dias → deletado.
3. `run_naoDeletaExpiradosDentroDaJanelaDeRetencao` — `expiresAt` há <7 dias → permanece.
4. `run_deletaTokensUsadosHaMaisDeRetencao` — `usedAt` há >7 dias → deletado.

### `ActivationTokenCleanupJobTest` (`@DataJpaTest`)
Mesmos 4 casos usando `ActivationToken`/`ActivationTokenRepository`.

### Regressão
- `RefreshTokenServiceTest`, `PasswordResetServiceTest`, `RefreshTokenCleanupJobTest` continuam passando sem alteração.
- `UserControllerIntegrationTest` (cobre fluxos de ativação/reset end-to-end) continua passando.

## Critérios de Aceitação

- `generatePlaintext()`/`sha256()` existem em um único lugar (`OpaqueTokenGenerator`).
- Os 3 services delegam para `OpaqueTokenGenerator.*` sem nenhuma cópia local.
- As 3 entidades estendem `AbstractOpaqueToken` sem nenhuma migration Flyway.
- `PasswordResetTokenCleanupJob` e `ActivationTokenCleanupJob` têm cobertura equivalente a `RefreshTokenCleanupJobTest`.
- Toda a suíte de testes do módulo `user` passa.

## Riscos e Mitigações

- **JPA/Flyway:** `@MappedSuperclass` não altera schema — risco zero. Verificar via `@DataJpaTest` que o mapeamento das 3 entidades continua correto.
- **Regressão em fluxos de autenticação:** paths críticos de segurança — exige rodar `UserControllerIntegrationTest` após cada migração de service.
