# A2 — Tratar `DataIntegrityViolationException` no `GlobalExceptionHandler`

## Objetivo

Adicionar um handler para `DataIntegrityViolationException` no `GlobalExceptionHandler`, retornando `409 Conflict` (`ErrorCode.CONFLICT`) em vez do atual `500 Internal Server Error` quando uma constraint `UNIQUE` do banco é violada (ex.: race condition TOCTOU em `UserService.register`/`updateUserProfile`).

Contexto completo do problema: [docs/roadmap/phase-1/A2-data-integrity-handler.md](../../roadmap/phase-1/A2-data-integrity-handler.md).

## Decisão de Design

- **ErrorCode:** mapear para `ErrorCode.CONFLICT` (genérico), não `EMAIL_ALREADY_REGISTERED`. O `GlobalExceptionHandler` é global (`@RestControllerAdvice`) e cobre qualquer entidade — inspecionar a mensagem da causa para diferenciar "email" de outras constraints acoplaria o handler a strings do driver JDBC/Postgres. `CONFLICT` já existe em `ErrorCode`, nenhuma adição necessária.

## Implementação

### `GlobalExceptionHandler.java`

Novo `@ExceptionHandler(DataIntegrityViolationException.class)`, seguindo o padrão dos handlers existentes (`build`, `logClientError`):

```java
@ExceptionHandler(DataIntegrityViolationException.class)
public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(
        DataIntegrityViolationException ex, HttpServletRequest req) {
    logClientError(ErrorCode.CONFLICT, req, "Constraint de unicidade violada");
    return build(ErrorCode.CONFLICT, "Recurso já existe ou viola uma restrição única", req.getRequestURI());
}
```

- Reaproveita `build`/`logClientError`/`problemDetail` existentes.
- Log em `WARN` (via `logClientError`) — não enviado ao Sentry como `ERROR`.
- `requestId` consistente via `MDC`, igual aos demais handlers.

### `ErrorCode.java`

Sem alterações — `CONFLICT(HttpStatus.CONFLICT, "Conflito")` já existe.

## Estratégia de Testes

Em vez de reproduzir uma race condition real via HTTP (não-determinístico), dois testes complementares cobrem o fluxo ponta a ponta:

1. **`GlobalExceptionHandlerTest` (novo, unit test):**
   - Chama `handleDataIntegrityViolation` com `DataIntegrityViolationException` mockada e `HttpServletRequest` mockado.
   - Assert: status `409`, `ProblemDetail.code == "CONFLICT"`, `requestId` presente quando setado no MDC.

2. **`UserServiceTest` (existente, novos casos):**
   - Mock `userRepository.findByEmail()` retornando vazio (passa no check TOCTOU atual).
   - Mock `userRepository.save()` lançando `DataIntegrityViolationException`.
   - Assert: `register`/`updateUserProfile` propagam a exceção (não a capturam/envolvem), confirmando que ela chegaria ao `GlobalExceptionHandler` em produção.

Esses dois testes, combinados, demonstram: "se `save()` falhar por violação de unicidade, a API responde `409 CONFLICT` em vez de `500`".

## Fora de Escopo

- Corrigir o TOCTOU em si (checar e depois salvar em operações separadas) — não faz parte desta tarefa.
- Teste de integração com concorrência real (múltiplas threads/requests).
- Diferenciar `EMAIL_ALREADY_REGISTERED` de outras violações de constraint.

## Critérios de Aceitação

- `DataIntegrityViolationException` não cai mais no handler genérico `Exception.class` (não retorna mais `500`).
- Resposta `409 Conflict`, corpo `ProblemDetail` no formato padrão, `code: "CONFLICT"`.
- Log classificado como erro de cliente (`WARN`, não enviado ao Sentry como `ERROR`).
- `GlobalExceptionHandlerTest` e novos casos em `UserServiceTest` passam.
