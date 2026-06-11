---
id: B5
phase: 2
priority: high
complexity: low
estimate: 0.5d
status: pending
depends_on: []
---

# B5 — `LoginRequest` como `record` + `@Valid`

## Objetivo

Padronizar `UserController.LoginRequest` para `record` (consistente com o restante do projeto) e habilitar validação de entrada (`@Valid`) no endpoint de login, hoje ausente.

## Problema Atual

`UserController.LoginRequest` (linhas ~140–159) é uma classe interna estática com getters/setters escritos manualmente, enquanto o restante do projeto usa `record` para DTOs imutáveis simples (ex.: `RefreshRequest`, `ResetPasswordRequest`, `TokenPairResponse`) ou `@Getter @Setter` do Lombok para DTOs maiores. Essa inconsistência estilística afeta legibilidade/manutenção.

Adicionalmente, o endpoint de login não possui validação de entrada (`@Valid`) — `email`/`password` podem chegar vazios/malformados sem rejeição prévia.

## Impacto

- Inconsistência estilística pequena, mas reduz legibilidade/manutenibilidade do `UserController`.
- Ausência de validação no login permite payloads malformados (ex.: `email` vazio) chegarem à camada de autenticação sem feedback claro de erro de validação.

## Arquivos Afetados

- `src/main/java/com/devappmobile/flowfuel/user/UserController.java` (classe interna `LoginRequest` e endpoint de login)
- Testes:
  - `src/test/java/com/devappmobile/flowfuel/user/UserControllerIntegrationTest.java`

## Requisitos Técnicos

- Substituir a classe interna `LoginRequest` por:
  ```java
  public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {}
  ```
- Adicionar `@Valid` no parâmetro do endpoint de login que recebe `LoginRequest`.
- Garantir que todas as referências a `getEmail()`/`getPassword()` (ou setters) sejam atualizadas para os acessores de record (`email()`, `password()`).
- Manter o nome da classe (`LoginRequest`) e seu local (interna a `UserController`, ou avaliar mover para arquivo próprio se já houver um padrão de DTOs em arquivo separado — verificar convenção do pacote `user`).

## Passos de Implementação

1. Localizar `UserController.LoginRequest` e todos os usos de `getEmail()`/`getPassword()`/setters relacionados.
2. Substituir a classe por `record LoginRequest(@NotBlank @Email String email, @NotBlank String password)`.
3. Adicionar `@Valid` ao parâmetro `LoginRequest` no método de login do controller.
4. Atualizar quaisquer referências internas (controller/service) para os novos acessores de record.
5. Rodar testes de integração de login.

## Critérios de Aceitação

- `LoginRequest` é um `record` com `@NotBlank @Email` em `email` e `@NotBlank` em `password`.
- Endpoint de login usa `@Valid` e retorna `400` com erros por campo para payloads inválidos (ex.: `email` malformado, `password` vazio).
- Login válido continua funcionando exatamente como antes (sem mudança de contrato para clientes que já enviam payloads válidos).

## Estratégia de Testes

- **Integration tests (`UserControllerIntegrationTest`):**
  - `POST /auth/login` com `email` malformado → `400` com erro de validação no campo `email`.
  - `POST /auth/login` com `password` vazio → `400` com erro de validação no campo `password`.
  - Login válido (credenciais corretas e incorretas) continua retornando os códigos de status já esperados (`200`/`401`).

## Riscos

- Muito baixo risco — mudança isolada e de baixo esforço.
- Verificar que nenhum outro código (ex.: serialização customizada, reflection) dependa da classe `LoginRequest` ter getters/setters no estilo JavaBean.

## Dependências

Nenhuma. Pode ser feito em paralelo com **[[M8-user-update-dto-validation]]** — como ambos tocam `UserController.java`, agrupar no mesmo PR para evitar conflitos de merge.

## Estimativa

0,5 dia.

## Checklist

- [ ] Analisar código atual
- [ ] Implementar solução
- [ ] Adicionar testes
- [ ] Atualizar documentação
- [ ] Executar testes de regressão
- [ ] Abrir PR
