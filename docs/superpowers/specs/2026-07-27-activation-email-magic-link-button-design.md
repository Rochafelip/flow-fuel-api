# Design: Email de ativação com botão de link (1 clique)

**Data:** 2026-07-27
**Status:** aprovado

## Contexto

O email de ativação de conta hoje mostra um código opaco de 32 bytes Base64 (`OpaqueTokenGenerator.generatePlaintext()`) que o usuário precisa copiar e colar manualmente na tela de ativação do app ([SmtpAccountActivationNotifier.java:73-138](../../../src/main/java/com/devappmobile/flowfuel/user/SmtpAccountActivationNotifier.java#L73-L138)). Em mobile isso é um atrito real: selecionar o texto, copiar, trocar de app, colar.

O campo `linkBaseUrl` já existe no `SmtpAccountActivationNotifier` (`@Value("${flowfuel.account-activation.link-base-url:...}")`, linha 39-40) mas **nunca é usado** no corpo do email — é campo morto hoje. Já existe um `ActivationLinkValidator` (fail-fast em prod/staging se `link-base-url` apontar para `localhost`) e um `LoggingAccountActivationNotifier` (stub de dev) que já monta o formato de URL pretendido:

```java
log.info("... {}?token={}&email={}", linkBaseUrl, activationToken, encodedEmail);
```

Ou seja, o link de ativação já foi planejado — inclusive o design anterior [`2026-06-24-magic-link-auto-login-design.md`](2026-06-24-magic-link-auto-login-design.md) já fez `POST /auth/activate` devolver `TokenPairResponse` (login automático). Só faltou colocar esse link de fato no email real (SMTP). Este spec fecha essa lacuna.

**Fora deste repositório:** a SPA web (porta 5173) e o app nativo (outro repositório) precisam dar `POST /auth/activate` com o token assim que a página/deep link abrir, para que o clique no email já resulte em conta ativada e logada. Isso já é esperado pelo design de 2026-06-24; este spec não depende de mudar esse comportamento, só de expor o link corretamente no email.

## Decisão de Design

### `SmtpAccountActivationNotifier`: trocar código por link/botão

- **Antes:** `htmlBody`/`plainBody` mostram o token como bloco de texto monoespaçado para copiar.
- **Depois:** ambos os corpos usam uma URL `{linkBaseUrl}?token={activationToken}&email={email codificado}` — mesmo formato já usado pelo `LoggingAccountActivationNotifier`.
  - **HTML:** vira um botão (`<a href="...">Ativar conta</a>`) estilizado, mantendo a paleta/spacing atual do template.
  - **Plain text:** vira a URL crua, precedida de instrução ("Clique no link abaixo para ativar sua conta").
- **Sem fallback de código:** por decisão do usuário, o código deixa de existir no email. Se o link falhar, o caminho é pedir reenvio via `POST /auth/resend-activation` (endpoint já existente, sem mudanças).
- **Nenhuma mudança de endpoint/contrato:** `POST /auth/activate`, geração/validação/hash do token, TTL (60 min) e o fluxo de uso único continuam exatamente como estão.

### Por que não um GET que ativa direto no backend

Clientes de email corporativos (Outlook Safe Links, proxies antivírus) costumam pré-buscar (GET) links de email para escanear malware antes do usuário abrir o email de fato. Se a ativação acontecesse num GET direto no backend, esse pré-scan consumiria o token (uso único) antes do clique real do usuário, quebrando o fluxo. Como a ativação continua exigindo um `POST /auth/activate` disparado via JS (SPA) ou HTTP client nativo (app) — algo que scanners de email tipicamente não executam — o link sobrevive ao pré-scan. Isso não exige nenhuma mudança de endpoint: é uma propriedade que já vem de graça do contrato atual (`POST`, não `GET`).

### `email` como query param

Mantido por paridade com o `LoggingAccountActivationNotifier` já existente (mesmo formato de URL nos dois notifiers). Não é usado para autenticação/validação — o lookup do token continua sendo só pelo hash SHA-256 do `token`. Serve apenas para a SPA/app poder pré-preencher UI (ex.: "ativando conta de fulano@email.com") sem precisar decodificar o token para isso.

## Fora de escopo

- Nenhuma mudança em `AccountActivationService`, `UserController`, `OpaqueTokenGenerator`, `ActivationToken`, TTL ou rate limiting.
- Nenhuma mudança na SPA web ou no app nativo (repositórios separados) — este spec só garante que o link correto chega ao email; o consumo automático do link é responsabilidade desses outros repositórios (já contemplada pelo design de 2026-06-24).
- `LoggingAccountActivationNotifier` já está correto, não muda.

## Arquivos Modificados

```
src/main/java/com/devappmobile/flowfuel/user/SmtpAccountActivationNotifier.java
src/test/java/com/devappmobile/flowfuel/user/SmtpAccountActivationNotifierTest.java  (novo)
```

## Testes

### `SmtpAccountActivationNotifierTest` (novo — hoje não existe nenhum teste para esta classe)

1. `sendActivationLink_incluiUrlComTokenEEmailNoHtml` — corpo HTML contém `linkBaseUrl + "?token=" + token + "&email=" + email codificado`.
2. `sendActivationLink_incluiUrlComTokenEEmailNoPlainText` — mesmo para o corpo plain-text.
3. `sendActivationLink_naoExpoeMaisOCodigoBruto` — garante que o token não aparece isolado (fora da URL) em nenhum dos dois corpos, ou seja, o formato antigo (bloco de código pra copiar) foi de fato removido.
4. `sendActivationLink_codificaEmailComCaracteresEspeciais` — email com `+`/acentos é URL-encoded corretamente na query string.

## Critérios de Aceitação

- Email de ativação (SMTP real) contém um link/botão clicável no formato `{linkBaseUrl}?token={token}&email={email}`, igual ao já logado por `LoggingAccountActivationNotifier`.
- Nenhum código de ativação aparece mais como texto solto para copiar manualmente.
- `POST /auth/activate` e `POST /auth/resend-activation` continuam com o mesmo contrato (nenhuma mudança).
- Testes novos de `SmtpAccountActivationNotifierTest` passam; suíte completa do módulo `user` continua verde.

## Riscos e Mitigações

- **Clientes de email que bloqueiam botões/links estilizados:** o plain-text (fallback do `multipart/alternative`) sempre traz a URL crua, clicável em qualquer client que renderize texto.
- **Pré-scan de link por segurança corporativa queimando o token:** mitigado por design — a ativação só ocorre via `POST` disparado por JS/app, não pelo `GET` que um scanner faria ao pré-buscar a página de destino.
- **SPA/app ainda não implementarem o auto-`POST` on load:** risco fora deste repositório; se não implementado, o usuário cai numa tela que exige colar o token manualmente (pior caso é igual ao comportamento atual, não uma regressão).
