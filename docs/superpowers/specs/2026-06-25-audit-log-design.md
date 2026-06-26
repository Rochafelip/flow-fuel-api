# AuditLog — Registro de Ações Sensíveis de Usuário

## Contexto

Hoje não há nenhum rastro persistente de ações sensíveis sobre contas (login, troca de senha, exclusão de conta, ativação, reset de senha). Em caso de investigação de incidente (conta comprometida, disputa de exclusão indevida) não há como reconstruir "quem fez o quê, quando, de onde".

## Objetivo

Registrar, em tabela própria, toda ocorrência **bem-sucedida** de:
- Login (`AuthService.login`)
- Troca de senha (`AuthService.changePassword`)
- Exclusão de conta (`AuthService.deleteUser`)
- Ativação de conta (`AccountActivationService.activate`)
- Reset de senha (`PasswordResetService.reset`)

Login falho (senha errada, e-mail inexistente) **não** gera entrada nesta entrega — fica fora de escopo (já cabe a logs de aplicação).

## Fora de escopo

- Gestão de admin via endpoint (promoção/demoção) — `is_admin` é setado manualmente via SQL.
- Auto-consulta do usuário aos próprios logs — só admin lê, nesta entrega.
- Captura de User-Agent — só IP é registrado.
- Qualquer alteração de comportamento dos fluxos existentes além de adicionar a chamada de log.

## Modelo de dados

Migration `V9__audit_log_and_admin.sql`:

```sql
ALTER TABLE users ADD COLUMN is_admin BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE audit_logs (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT,            -- snapshot, sem FK (sobrevive à exclusão do usuário)
    action VARCHAR(32) NOT NULL,
    ip_address VARCHAR(45),
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_logs_user_id ON audit_logs(user_id);
CREATE INDEX idx_audit_logs_action ON audit_logs(action);
```

`audit_logs.user_id` é deliberadamente **sem FK** — ao excluir uma conta, o log de `ACCOUNT_DELETION` precisa preservar o id numérico mesmo depois que a linha em `users` deixa de existir.

`User.isAdmin` (boolean, default `false`) é setado manualmente (`UPDATE users SET is_admin = true WHERE id = ...`); não há endpoint de gestão.

Entidade `AuditLog` (JPA, sem relacionamento `@ManyToOne` para `User` — só `Long userId` puro, coerente com a ausência de FK).

Enum `AuditAction`: `LOGIN`, `PASSWORD_CHANGE`, `ACCOUNT_DELETION`, `ACCOUNT_ACTIVATION`, `PASSWORD_RESET`.

## Gravação do log

`AuditLogService.record(Long userId, AuditAction action)`:
1. Extrai IP da requisição atual via `RequestContextHolder.currentRequestAttributes()` (mesma lógica de `X-Forwarded-For`/`remoteAddr` já usada em `RateLimitFilter.java:100-104` — extrair para um utilitário compartilhado, ex: `common/ClientIpResolver.java`, usado pelos dois pontos).
2. Persiste `AuditLog(userId, action, ip, now())`.

Chamado inline (sem eventos/AOP) ao final de cada fluxo de sucesso:
- `AuthService.login` → `AuditAction.LOGIN`, após autenticação validada, antes de retornar os tokens.
- `AuthService.changePassword` → `AuditAction.PASSWORD_CHANGE`, após persistir a nova senha.
- `AuthService.deleteUser` → `AuditAction.ACCOUNT_DELETION`, **antes** de excluir o usuário (para garantir que a chamada tenha contexto de requisição válido; a entrada de log já não depende de FK, então a ordem em relação à exclusão não compromete a integridade).
- `AccountActivationService.activate` → `AuditAction.ACCOUNT_ACTIVATION`, após marcar a conta como `ACTIVE`.
- `PasswordResetService.reset` → `AuditAction.PASSWORD_RESET`, após persistir a nova senha.

Falha ao gravar o audit log (ex: erro de banco) não deve quebrar o fluxo principal — `AuditLogService.record` captura e loga exceções internamente (log de warning), nunca propaga. `[decisão de design — auditoria é best-effort, não pode bloquear login/exclusão/etc.]`

## Endpoint de leitura

`GET /audit-logs` — novo `AuditLogController`:
- Autorização: `AuthorizationHelper.ensureIsAdmin(user)` (novo método, mesmo padrão dos `ensureOwnsX` existentes) → `403 FORBIDDEN_OPERATION` se `!user.isAdmin()`.
- Paginação: `?page=0&size=20` (`@PageableDefault(size = 20)`), resposta em `PageResponseDTO<AuditLogResponseDTO>` — mesma convenção documentada em `docs/README.md#convenção-de-paginação`.
- Filtros opcionais via query: `userId` (Long), `action` (enum `AuditAction`).
- `AuditLogResponseDTO { id, userId, action, ipAddress, createdAt }`.
- Sem filtro de data nesta entrega (pode ser adicionado depois, seguindo o padrão `startDate`/`endDate` já usado em Refuels/VehicleEvents).

## Plano de testes

- Testes unitários: `AuditLogService.record` persiste corretamente; falha de persistência não propaga exceção.
- Testes de integração: cada um dos 5 fluxos gera exatamente 1 `AuditLog` com a `action` correta após sucesso; login falho não gera entrada.
- Teste de autorização: `GET /audit-logs` retorna 403 para usuário não-admin, 200 com página de resultados para admin.
- Teste de migration: `V9` aplica sem erro sobre o schema atual (V1-V8).

## Riscos / Pontos de atenção

- `is_admin` setado manualmente via SQL é um processo manual e sem auditoria própria — aceitável para o escopo atual (poucos admins, baixa frequência de mudança), mas é uma lacuna se o time crescer.
- Ausência de FK em `audit_logs.user_id` significa que IDs de usuários nunca reaproveitados (sequence do Postgres já garante isso) são a única proteção contra colisão de referência após exclusão.
