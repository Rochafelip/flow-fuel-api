-- Ativacao de conta (confirmacao de email).
-- 1) Coluna de status no usuario. Default ACTIVE faz o backfill correto das contas
--    ja existentes (criadas no fluxo antigo, que ja logava no cadastro). Novas contas
--    recebem PENDING_ACTIVATION explicitamente pela aplicacao.
-- 2) Tabela de tokens de ativacao: mesmo padrao dos refresh / password reset tokens
--    (ADR-003) — grava apenas o SHA-256 do token; o plaintext nunca trafega para o
--    banco. Token de uso unico (used_at) e curta duracao (expires_at).

ALTER TABLE users ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE';

CREATE TABLE activation_tokens (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    expires_at  TIMESTAMP NOT NULL,
    created_at  TIMESTAMP NOT NULL,
    used_at     TIMESTAMP
);

CREATE INDEX idx_activation_tokens_user_id ON activation_tokens(user_id);
CREATE INDEX idx_activation_tokens_token_hash ON activation_tokens(token_hash);
