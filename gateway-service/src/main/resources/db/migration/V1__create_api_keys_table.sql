CREATE TABLE api_keys (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    key_hash        VARCHAR(255) UNIQUE NOT NULL,
    name            VARCHAR(100) NOT NULL,
    owner           VARCHAR(100) NOT NULL,
    rate_limit_rpm  INT NOT NULL DEFAULT 60,
    rate_limit_tpm  INT NOT NULL DEFAULT 100000,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    last_used_at    TIMESTAMP
);

CREATE INDEX idx_api_keys_key_hash ON api_keys (key_hash);
CREATE INDEX idx_api_keys_enabled ON api_keys (enabled);
