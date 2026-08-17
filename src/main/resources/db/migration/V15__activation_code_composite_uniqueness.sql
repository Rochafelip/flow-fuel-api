-- Codigo de ativacao passa de token opaco (alta entropia) para codigo numerico
-- de 6 digitos: apenas 1.000.000 de valores possiveis, entao colisoes de
-- token_hash entre usuarios diferentes sao esperadas e nao devem mais violar
-- unicidade global. A unicidade passa a ser por (user_id, token_hash).

ALTER TABLE activation_tokens DROP CONSTRAINT activation_tokens_token_hash_key;
DROP INDEX IF EXISTS idx_activation_tokens_token_hash;

CREATE UNIQUE INDEX idx_activation_tokens_user_id_token_hash
    ON activation_tokens(user_id, token_hash);
