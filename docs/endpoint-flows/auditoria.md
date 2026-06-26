# Fluxo de Endpoints — Auditoria (AuditLog)

> Fonte: `audit/AuditLogController.java`, `audit/AuditLogService.java`, `audit/AuditLogRepository.java`, `audit/AuditLog.java`, `common/AuthorizationHelper.java`.

Controller: `@RequestMapping("/audit-logs")`. Rota protegida por JWT (autenticação obrigatória) e, adicionalmente, por `AuthorizationHelper.ensureIsAdmin(user)` — só usuários com `User.isAdmin = true` recebem `200`; os demais recebem `403 FORBIDDEN_OPERATION`. Não há endpoint de promoção a admin: a coluna `users.is_admin` é setada manualmente via SQL.

## `GET /audit-logs` — listar (filtro userId/action, paginado)

`AuditLogService.search` monta a query conforme os filtros presentes (`userId`+`action`, só `userId`, só `action`, ou nenhum filtro), sempre ordenada por `createdAt DESC`. Paginação via `?page=0&size=20` (`@PageableDefault(size = 20)`). Resposta em `PageResponseDTO<AuditLogResponseDTO>` — ver convenção em [docs/README.md](../README.md#convenção-de-paginação).

## Geração das entradas (não é um endpoint, é efeito colateral de outros fluxos)

`AuditLogService.record(userId, action)` é chamado, sempre após sucesso, em:
- `AuthService.login` (`user/AuthService.java`) → `LOGIN`, gravado depois de `tokenIssuer.issueTokenPair`
- `AuthService.changePassword` (`user/AuthService.java`) → `PASSWORD_CHANGE`, gravado depois de salvar a nova senha e revogar os refresh tokens
- `AuthService.deleteUser` (`user/AuthService.java`) → `ACCOUNT_DELETION`, gravado **antes** de `userRepository.deleteById` (a entrada precisa existir antes da exclusão efetiva, já que `audit_logs.user_id` não tem FK)
- `AccountActivationService.activate` (`user/AccountActivationService.java`) → `ACCOUNT_ACTIVATION`, gravado depois de ativar a conta e antes de emitir o par de tokens
- `PasswordResetService.reset` (`user/PasswordResetService.java`) → `PASSWORD_RESET`, gravado depois de salvar a nova senha e revogar os refresh tokens

`record` nunca propaga exceção (best-effort, `try/catch` interno com log em `WARN`) — uma falha ao gravar audit log não afeta o fluxo de negócio que o originou. `audit_logs.user_id` não tem FK para `users` (mapeamento via `@Column` simples, sem `@ManyToOne`): é um snapshot que sobrevive à exclusão da conta.

## Pontos de Atenção

- Login falho (senha errada, email inexistente) não gera entrada de audit log nesta versão — só sucesso é registrado. `[decisão de design]`
- Não há captura de User-Agent, só IP (via `ClientIpResolver`, compartilhado com `RateLimitFilter`).
- Promoção/demoção de admin é 100% manual (SQL direto) — sem auditoria própria desse processo.
- O IP só é resolvido quando há um `RequestContextHolder` ativo (`AuditLogService.currentClientIp`); fora de contexto de requisição HTTP, `ipAddress` fica `null`.
