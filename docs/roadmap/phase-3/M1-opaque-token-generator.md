---
id: M1
phase: 3
priority: medium
complexity: medium-high
estimate: 3-4d
status: pending
depends_on: []
---

# M1 — Extrair `OpaqueTokenGenerator` + `AbstractOpaqueToken`

## Objetivo

Eliminar a duplicação de lógica de geração/hash de "token opaco" presente em `RefreshTokenService`, `PasswordResetService` e `AccountActivationService`, extraindo um utilitário compartilhado (`OpaqueTokenGenerator`) e, opcionalmente, uma superclasse mapeada (`AbstractOpaqueToken`) para as três entidades de token.

## Problema Atual

Os métodos `generatePlaintext()` e `sha256(...)` são copiados **idênticos** em três classes:
- `RefreshTokenService`
- `PasswordResetService`
- `AccountActivationService`

As três entidades (`RefreshToken`, `PasswordResetToken`, `ActivationToken`) têm exatamente os mesmos campos: `tokenHash`, `expiresAt`, `createdAt`, `usedAt`/`revokedAt`, e os mesmos métodos `isUsable()`/`isExpired()`.

Os três jobs de cleanup (`RefreshTokenCleanupJob`, `PasswordResetTokenCleanupJob`, `ActivationTokenCleanupJob`) têm a mesma estrutura (`@Scheduled` + `deleteOldTokens(cutoff)`).

## Impacto

- **Risco de segurança:** qualquer correção futura (ex.: trocar `SecureRandom` por outro algoritmo, aumentar `TOKEN_BYTES`, mudar o algoritmo de hash de SHA-256 para outro) precisa ser replicada em 3 lugares — alto risco de inconsistência/esquecimento, podendo deixar um dos três fluxos com configuração de segurança desatualizada.
- **Lacuna de teste identificada no relatório (item 15):** apenas `RefreshTokenCleanupJobTest` existe; `PasswordResetTokenCleanupJob` e `ActivationTokenCleanupJob` não têm testes próprios, apesar de estruturalmente idênticos ao primeiro.

## Arquivos Afetados

- Novo arquivo: `src/main/java/com/devappmobile/flowfuel/common/security/OpaqueTokenGenerator.java` (ou pacote equivalente)
- Opcional: novo `@MappedSuperclass AbstractOpaqueToken` (ex.: `src/main/java/com/devappmobile/flowfuel/common/security/AbstractOpaqueToken.java`)
- `src/main/java/com/devappmobile/flowfuel/user/RefreshToken.java`, `RefreshTokenService.java`, `RefreshTokenRepository.java`, `RefreshTokenCleanupJob.java`
- `src/main/java/com/devappmobile/flowfuel/user/PasswordResetToken.java`, `PasswordResetService.java`, `PasswordResetTokenRepository.java`, `PasswordResetTokenCleanupJob.java`
- `src/main/java/com/devappmobile/flowfuel/user/ActivationToken.java`, `AccountActivationService.java`, `ActivationTokenRepository.java`, `ActivationTokenCleanupJob.java`
- Testes:
  - `src/test/java/com/devappmobile/flowfuel/user/RefreshTokenServiceTest.java`
  - `src/test/java/com/devappmobile/flowfuel/user/RefreshTokenCleanupJobTest.java`
  - `src/test/java/com/devappmobile/flowfuel/user/PasswordResetServiceTest.java`
  - Novos: `PasswordResetTokenCleanupJobTest`, `ActivationTokenCleanupJobTest`
  - Novo: `OpaqueTokenGeneratorTest`

## Requisitos Técnicos

1. **`OpaqueTokenGenerator`** (classe utilitária, métodos estáticos ou `@Component`):
   ```java
   public final class OpaqueTokenGenerator {
       private static final int TOKEN_BYTES = 32;
       private static final SecureRandom RNG = new SecureRandom();

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
2. **(Opcional) `AbstractOpaqueToken`** — `@MappedSuperclass` com `tokenHash`, `expiresAt`, `createdAt`, `usedAt`/`revokedAt` (avaliar se os 3 tokens usam exatamente o mesmo nome de campo para "consumido"; `RefreshToken` usa `revokedAt`, os outros podem usar `usedAt` — decidir se vale generalizar ou manter campos específicos nas subclasses), `isExpired()`, `isUsable()`.
3. Atualizar as 3 entidades para estender `AbstractOpaqueToken` (se adotado) e os 3 services para usar `OpaqueTokenGenerator.generatePlaintext()`/`sha256(...)`.
4. **Unificar/generalizar a estrutura dos 3 jobs de cleanup** (`@Scheduled` + `deleteOldTokens(cutoff)`) — avaliar extrair um template method ou classe base abstrata para os jobs, mantendo o agendamento (`@Scheduled`) específico de cada um.
5. **Adicionar testes para `PasswordResetTokenCleanupJob` e `ActivationTokenCleanupJob`** (lacuna identificada no relatório, item 15) — espelhar `RefreshTokenCleanupJobTest`.

## Passos de Implementação

1. Mapear `generatePlaintext()`/`sha256(...)` nos 3 services e confirmar que são idênticos.
2. Criar `OpaqueTokenGenerator` com os métodos consolidados + `OpaqueTokenGeneratorTest`.
3. Substituir as 3 implementações locais pelas chamadas ao novo utilitário, um service por vez, rodando os testes após cada substituição.
4. Avaliar viabilidade de `AbstractOpaqueToken` (`@MappedSuperclass`):
   - Comparar campos das 3 entidades (`RefreshToken`, `PasswordResetToken`, `ActivationToken`).
   - Se os campos forem suficientemente equivalentes, criar a superclasse e migrar as 3 entidades.
   - Se houver divergências significativas (ex.: `revokedAt` vs `usedAt` com semânticas diferentes), documentar a decisão de **não** unificar entidades e manter apenas o utilitário de geração/hash.
5. Avaliar e, se viável, unificar a estrutura dos 3 jobs de cleanup (template method ou classe base).
6. Adicionar `PasswordResetTokenCleanupJobTest` e `ActivationTokenCleanupJobTest` espelhando `RefreshTokenCleanupJobTest`.
7. Rodar a suíte completa de testes de `user` (services, jobs, repositórios).

## Critérios de Aceitação

- `generatePlaintext()`/`sha256(...)` existem em **um único lugar** (`OpaqueTokenGenerator`) e são usados pelos 3 services.
- Comportamento de geração/validação de tokens (refresh, reset de senha, ativação) permanece idêntico (mesmos formatos, mesmo algoritmo de hash).
- `PasswordResetTokenCleanupJob` e `ActivationTokenCleanupJob` possuem cobertura de teste equivalente a `RefreshTokenCleanupJobTest`.
- Decisão sobre `AbstractOpaqueToken` está documentada (implementada ou justificadamente descartada).
- Toda a suíte de testes de `user` (incluindo `UserControllerIntegrationTest`, que cobre fluxos de ativação/reset) continua passando.

## Estratégia de Testes

- **`OpaqueTokenGeneratorTest`:** validar formato/tamanho do token gerado (Base64 URL-safe, 32 bytes), determinismo do hash SHA-256 para uma mesma entrada, e que hashes de entradas diferentes diferem.
- **Regressão nos 3 services:** `RefreshTokenServiceTest`, `PasswordResetServiceTest`, e testes de `AccountActivationService` (via `UserControllerIntegrationTest`, que cobre o fluxo completo de ativação) continuam passando sem alteração de comportamento externo.
- **Novos testes de cleanup jobs:** `PasswordResetTokenCleanupJobTest` e `ActivationTokenCleanupJobTest`, cobrindo:
  - Tokens expirados/usados antes do cutoff são removidos.
  - Tokens válidos/recentes não são removidos.
- Se `AbstractOpaqueToken` for adotado, validar via `@DataJpaTest` que o mapeamento JPA das 3 entidades continua correto (colunas, tipos, constraints) — comparar com as migrations Flyway existentes (V1-V7) para garantir que nenhuma coluna mude de nome/tipo inadvertidamente.

## Riscos

- **Médio-alto risco** — toca 3 services + 3 entidades + 3 repositórios + 3 jobs + testes existentes; é a refatoração de maior superfície da Fase 3.
- Se `AbstractOpaqueToken` alterar nomes de colunas/mapeamento JPA, pode quebrar compatibilidade com o schema gerenciado por Flyway (`ddl-auto=validate` em prod/staging) — qualquer mudança de mapeamento deve vir acompanhada de migration Flyway correspondente, ou deve-se preservar os nomes de coluna originais via `@AttributeOverrides`.
- Risco de regressão silenciosa nos fluxos de autenticação/reset/ativação, que são caminhos críticos de segurança — exige testes de regressão completos antes do merge.

## Dependências

Nenhuma tecnicamente, mas **deve preceder [[M2-split-user-service]]** — `UserService`/futuro `AuthService` dependem desses serviços de token; extrair o utilitário compartilhado antes evita retrabalho no split de M2.

## Estimativa

3–4 dias. Inclui adicionar testes para `PasswordResetTokenCleanupJob`/`ActivationTokenCleanupJob` (lacuna identificada no relatório técnico, item 15).

## Checklist

- [ ] Analisar código atual
- [ ] Implementar solução
- [ ] Adicionar testes
- [ ] Atualizar documentação
- [ ] Executar testes de regressão
- [ ] Abrir PR
