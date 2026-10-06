CREATE TABLE provider_config (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_name     VARCHAR(50) UNIQUE NOT NULL,
    base_url          VARCHAR(255) NOT NULL,
    api_key_encrypted VARCHAR(500),
    enabled           BOOLEAN NOT NULL DEFAULT TRUE,
    priority          INT NOT NULL DEFAULT 0,
    model_mappings    JSONB NOT NULL DEFAULT '{}'
);

INSERT INTO provider_config (provider_name, base_url, priority, model_mappings)
VALUES
    ('openai', 'https://api.openai.com', 1, '{"gpt-4o": "llama3", "gpt-4o-mini": "llama3"}'),
    ('ollama', 'http://localhost:11434', 2, '{"llama3": "gpt-4o"}');
