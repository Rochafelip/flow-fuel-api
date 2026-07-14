CREATE TABLE device_tokens (
    token VARCHAR(255) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    platform VARCHAR(20) NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_device_tokens_user_id ON device_tokens(user_id);
