-- Tabela de refresh tokens (ADR-003).
-- Armazena o hash SHA-256 do token; o plaintext nunca trafega para o banco.
-- replaced_by_id viabiliza deteccao de re-uso: se um token revogado for
-- apresentado novamente, toda a cadeia daquele usuario deve ser invalidada.

CREATE TABLE refresh_tokens (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash      VARCHAR(64) NOT NULL UNIQUE,
    expires_at      TIMESTAMP NOT NULL,
    created_at      TIMESTAMP NOT NULL,
    revoked_at      TIMESTAMP,
    replaced_by_id  BIGINT REFERENCES refresh_tokens(id)
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_token_hash ON refresh_tokens(token_hash);
