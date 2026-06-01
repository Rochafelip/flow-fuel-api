-- Tabela de tokens de redefinicao de senha (fluxo "esqueci minha senha").
-- Mesmo padrao dos refresh tokens (ADR-003): grava apenas o SHA-256 do token;
-- o plaintext nunca trafega para o banco. Token de uso unico (used_at) e curta
-- duracao (expires_at).

CREATE TABLE password_reset_tokens (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    expires_at  TIMESTAMP NOT NULL,
    created_at  TIMESTAMP NOT NULL,
    used_at     TIMESTAMP
);

CREATE INDEX idx_password_reset_tokens_user_id ON password_reset_tokens(user_id);
CREATE INDEX idx_password_reset_tokens_token_hash ON password_reset_tokens(token_hash);
