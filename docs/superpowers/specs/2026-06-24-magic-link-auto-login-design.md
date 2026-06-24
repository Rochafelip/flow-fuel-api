# Design: Auto-login via Magic Link na ativação de conta

**Data:** 2026-06-24
**Status:** aprovado

## Contexto

Hoje a ativação de conta é um fluxo de dois passos: o usuário se cadastra (`POST /auth/register`), recebe um email com link de ativação (`ActivationToken`, TTL 60 min, uso único, hash SHA-256 via `OpaqueTokenGenerator`), clica no link, o frontend chama `POST /auth/activate` com o token em texto puro, e o backend apenas marca o usuário como `ACTIVE` e responde `204 No Content`. O usuário ainda precisa fazer login manualmente com email/senha depois disso.

Objetivo: usar esse mesmo link de ativação como "magic link" — ao validar o token, o backend já autentica o usuário, emitindo o par de tokens JWT (access + refresh) na resposta do `POST /auth/activate`, eliminando o passo extra de login após o cadastro.

Este fluxo é especificamente para o caminho de **ativação de conta nova**. Login por senha continua existindo e inalterado para contas já ativas.

## Decisões de Design

### 1. `POST /auth/activate` passa a retornar `TokenPairResponse`

- **Antes:** `ResponseEntity<Void>` com status `204 No Content`.
- **Depois:** `ResponseEntity<TokenPairResponse>` com status `200 OK`, corpo `{ accessToken, refreshToken, expiresInSeconds }` — mesmo shape já usado por `POST /auth/login` e `POST /auth/refresh`.
- Requisição (`ActivateAccountRequest { token }`) não muda.
- Erros não mudam: token ausente/inválido/expirado/já usado continua lançando `AppException(ErrorCode.AUTH_ACTIVATION_INVALID, ...)`.

### 2. Extrair `TokenIssuer` para resolver dependência circular

Hoje `AuthService.issueTokenPair(User)` é `private` e usa `jwtUtil` + `refreshTokenService`. `AuthService` já depende de `AccountActivationService` (para `register()` chamar `sendActivation()`). Se `AccountActivationService.activate()` precisar emitir o par de tokens, ela não pode chamar de volta em `AuthService` — criaria um ciclo de dependência Spring.

**Solução:** extrair a lógica de `AuthService.issueTokenPair` (linhas 98-103 de `AuthService.java`) para um novo componente `TokenIssuer`:

```java
package com.devappmobile.flowfuel.user;

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

`AuthService` e `AccountActivationService` passam a injetar `TokenIssuer` em vez de duplicar a lógica. `AuthService.login()` chama `tokenIssuer.issueTokenPair(user)` no lugar do método privado removido.

**Rationale:** resolve o ciclo e remove a única duplicação de emissão de token que existiria entre os dois services. Não introduz abstração nova além do necessário — é a mesma lógica que já existia, movida para um lugar que ambos os consumidores podem acessar.

**Alternativa descartada:** injetar `AuthService` em `AccountActivationService` e quebrar o ciclo removendo a dependência inversa (`AuthService` parar de depender de `AccountActivationService`, movendo a orquestração de `register()` para o controller). Descartada por exigir mover lógica de negócio do service para o controller, o que é uma mudança maior e não relacionada ao objetivo desta feature.

### 3. `AccountActivationService.activate()` retorna `TokenPairResponse`

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

### 4. Segurança e expiração — sem mudanças

TTL (60 min, `ACCOUNT_ACTIVATION_TOKEN_TTL_MINUTES`), uso único (`usedAt`), hash SHA-256 e o cleanup job (`ActivationTokenCleanupJob`) continuam exatamente como estão — segue o padrão já estabelecido por `PasswordResetToken`. Esta feature não altera o ciclo de vida do `ActivationToken`, apenas o que acontece depois de validá-lo com sucesso.

### 5. Fora de escopo

- `POST /auth/resend-activation` não tem rate limiting hoje (diferente de `forgot-password`, que tem 3/hora). Esse é um gap pré-existente, não introduzido por esta mudança, e foi deixado de fora por decisão do usuário durante o brainstorming.
- Login por senha (`POST /auth/login`) não muda.
- Nenhuma migration Flyway é necessária — não há mudança de schema.

## Arquivos Novos

```
src/main/java/com/devappmobile/flowfuel/user/TokenIssuer.java
src/test/java/com/devappmobile/flowfuel/user/TokenIssuerTest.java
```

## Arquivos Modificados

```
src/main/java/com/devappmobile/flowfuel/user/AuthService.java
src/main/java/com/devappmobile/flowfuel/user/AccountActivationService.java
src/main/java/com/devappmobile/flowfuel/user/UserController.java
src/test/java/com/devappmobile/flowfuel/user/AuthServiceTest.java
docs/spec/openapi.yaml
docs/business-flows/autenticacao.md
docs/endpoint-flows/autenticacao-e-usuario.md
```

## Testes

### `TokenIssuerTest` (novo, mockando `JwtUtil` e `RefreshTokenService`)
1. `issueTokenPair_retornaAccessTokenERefreshToken` — verifica chamada a `jwtUtil.generateToken` e `refreshTokenService.issue`, e o shape do `TokenPairResponse`.
2. `issueTokenPair_usaTtlConfiguradoDoJwtUtil` — `expiresInSeconds` reflete `jwtUtil.getAccessTokenTtlMs() / 1000`.

### `AccountActivationServiceTest` (novo ou atualizado)
1. `activate_comTokenValido_ativaContaERetornaTokenPair` — verifica `user.status == ACTIVE`, `token.usedAt != null`, e que o `TokenPairResponse` retornado vem de `tokenIssuer.issueTokenPair(user)` (mock).
2. `activate_comTokenInvalido_lancaAuthActivationInvalid` — comportamento de erro inalterado.
3. `activate_comTokenJaUsado_lancaAuthActivationInvalid` — comportamento de erro inalterado.
4. `activate_comTokenExpirado_lancaAuthActivationInvalid` — comportamento de erro inalterado.

### `AuthServiceTest` (atualizado)
- `register_comEmailNovo_criaContaPendenteEDisparaAtivacao` — continua sem emitir JWT no registro (inalterado).
- `login_comContaPendente_lancaAccountNotActivated` — inalterado.
- Atualizar qualquer teste que verificava `issueTokenPair` privado para usar o mock de `TokenIssuer` injetado.

### Regressão
- `UserControllerIntegrationTest` (ou equivalente) — endpoint `POST /auth/activate` precisa ser atualizado para esperar `200 OK` com `TokenPairResponse` em vez de `204 No Content`.
- `PasswordResetServiceTest`, `RefreshTokenServiceTest` — sem mudanças esperadas (não tocados).

## Critérios de Aceitação

- `POST /auth/activate` com token válido retorna `200 OK` com `accessToken`, `refreshToken` e `expiresInSeconds` válidos, e o usuário consegue usar o `accessToken` imediatamente em uma rota autenticada sem fazer login separado.
- `POST /auth/activate` com token inválido/expirado/usado continua retornando o mesmo erro de antes (`AUTH_ACTIVATION_INVALID`).
- Nenhuma duplicação de lógica de emissão de JWT entre `AuthService` e `AccountActivationService` — ambos usam `TokenIssuer`.
- Nenhum ciclo de dependência Spring introduzido (`AuthService → AccountActivationService → TokenIssuer`, sem volta).
- `docs/spec/openapi.yaml` reflete o novo response schema de `/auth/activate`.
- Toda a suíte de testes do módulo `user` passa.

## Riscos e Mitigações

- **Ciclo de dependência Spring:** mitigado extraindo `TokenIssuer` como componente independente, sem depender de `AuthService` nem `AccountActivationService`.
- **Quebra de contrato para o frontend:** o frontend hoje espera `204 No Content` em `POST /auth/activate`. Mudar para `200` com corpo é breaking change de API — o frontend precisa ser atualizado em conjunto (fora do escopo deste repositório backend, mas deve ser comunicado/coordenado antes do deploy).
- **Reuso de token já ativado:** comportamento inalterado (token de uso único), sem risco novo.
