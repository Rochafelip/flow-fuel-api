---
id: A2
phase: 1
priority: critical
complexity: low
estimate: 0.5d
status: pending
depends_on: []
---

# A2 — Tratar `DataIntegrityViolationException` no `GlobalExceptionHandler`

## Objetivo

Adicionar um handler específico para `DataIntegrityViolationException` no `GlobalExceptionHandler`, retornando `409 Conflict` (`ErrorCode.EMAIL_ALREADY_REGISTERED` ou `CONFLICT` genérico) em vez do atual `500 Internal Server Error`.

## Problema Atual

`UserService.register` (linhas ~38–40) e `UserService.updateUserProfile` (linhas ~142–146) fazem `findByEmail(...).isPresent()` e, em seguida, chamam `save()` em uma operação separada (race condition clássica TOCTOU — time-of-check to time-of-use). Entre o `SELECT` e o `INSERT`/`UPDATE`, outra requisição concorrente pode inserir o mesmo e-mail.

A constraint `UNIQUE` no banco (definida em `V1__baseline.sql`) rejeita o segundo `INSERT`/`UPDATE`, lançando `DataIntegrityViolationException`. Essa exception **não é tratada** especificamente pelo `GlobalExceptionHandler` e cai no handler genérico (`Exception.class`), retornando `500 INTERNAL_ERROR`.

## Impacto

- Dois `POST /auth/register` simultâneos com o mesmo e-mail: um recebe `201`, o outro recebe `500 INTERNAL_ERROR` — quando o esperado seria `409 Conflict` / `EMAIL_ALREADY_REGISTERED`.
- O erro 500 é logado como erro inesperado e enviado ao **Sentry como `ERROR`**, gerando ruído/alertas falsos para a equipe de observabilidade.
- Resposta incorreta ao cliente da API (contrato `ErrorCode` violado nesse cenário específico).

## Arquivos Afetados

- `src/main/java/com/devappmobile/flowfuel/config/GlobalExceptionHandler.java`
- `src/main/java/com/devappmobile/flowfuel/common/error/ErrorCode.java`
- Testes:
  - `src/test/java/com/devappmobile/flowfuel/user/UserControllerIntegrationTest.java`
  - `src/test/java/com/devappmobile/flowfuel/user/UserServiceTest.java`

## Requisitos Técnicos

- Adicionar `@ExceptionHandler(DataIntegrityViolationException.class)` no `GlobalExceptionHandler`, seguindo o mesmo padrão RFC 7807 (`ProblemDetail`) já usado para os demais handlers.
- Mapear para `ErrorCode.CONFLICT` (genérico) ou, se for possível inspecionar a constraint violada de forma segura, `ErrorCode.EMAIL_ALREADY_REGISTERED` especificamente para violação do índice único de e-mail.
- Garantir que o log gerado para esse caso seja classificado como erro de cliente (não enviar ao Sentry como `ERROR` — usar o padrão de `logClientError` já existente no handler, conforme citado no relatório técnico).
- Manter consistência de `requestId`/`X-Request-Id` na resposta, como os demais handlers.

## Passos de Implementação

1. Ler `GlobalExceptionHandler` para entender o padrão existente de handlers (`build(...)`, `logClientError(...)`).
2. Verificar `ErrorCode` para confirmar se já existe `EMAIL_ALREADY_REGISTERED` e/ou `CONFLICT` genérico; adicionar se necessário.
3. Implementar:
   ```java
   @ExceptionHandler(DataIntegrityViolationException.class)
   public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(
           DataIntegrityViolationException ex, HttpServletRequest req) {
       logClientError(ErrorCode.CONFLICT, req, "Constraint de unicidade violada");
       return build(ErrorCode.CONFLICT, "Recurso já existe ou viola uma restrição única", req.getRequestURI());
   }
   ```
4. Avaliar se vale a pena inspecionar `ex.getMostSpecificCause().getMessage()` para diferenciar violação de e-mail único de outras constraints (decisão: manter simples com `CONFLICT` genérico, a menos que o esforço extra seja trivial).
5. Rodar testes existentes de `UserControllerIntegrationTest` relacionados a registro/atualização de e-mail duplicado.

## Critérios de Aceitação

- Um cadastro concorrente com e-mail duplicado retorna `409 Conflict` com corpo `ProblemDetail` no formato padrão do projeto, em vez de `500`.
- O mesmo vale para `updateUserProfile` quando o novo e-mail já existe para outro usuário.
- O evento não é mais enviado ao Sentry como `ERROR` (verificar nível de log usado).
- Testes de integração cobrindo o cenário de corrida (registro concorrente com mesmo e-mail) passam.

## Estratégia de Testes

- **Integration test:** simular violação de constraint única — pode ser feito inserindo diretamente um usuário com o e-mail alvo via repositório antes de chamar `register`/`updateUserProfile` com o mesmo e-mail dentro da mesma transação/contexto que força o `DataIntegrityViolationException` (em vez de depender apenas do check `findByEmail`).
- Validar resposta HTTP `409`, corpo `ProblemDetail` e `ErrorCode` retornado.
- **Unit test do handler:** testar `GlobalExceptionHandler.handleDataIntegrityViolation` isoladamente passando uma `DataIntegrityViolationException` mockada.
- Confirmar (via teste ou inspeção) que o log não é classificado como erro de servidor.

## Riscos

- Baixo risco — adição de um novo handler, não modifica handlers existentes.
- Risco de mapear `DataIntegrityViolationException` "demais" (capturando violações de outras constraints que talvez devessem ter mensagens mais específicas) — mitigar documentando que o handler é genérico para qualquer violação de unicidade/constraint.

## Dependências

Nenhuma. **Combina bem com [[M8-user-update-dto-validation]]** (mesma classe de problema de validação de entrada) — pode ser feito em sequência pelo mesmo desenvolvedor, mas não é bloqueante.

## Estimativa

0,5 dia.

## Checklist

- [ ] Analisar código atual
- [ ] Implementar solução
- [ ] Adicionar testes
- [ ] Atualizar documentação
- [ ] Executar testes de regressão
- [ ] Abrir PR
