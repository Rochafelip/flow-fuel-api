CREATE TABLE stored_files (
    "key" VARCHAR(255) PRIMARY KEY,
    content_type VARCHAR(100) NOT NULL,
    data BYTEA NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
