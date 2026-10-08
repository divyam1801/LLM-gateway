# Gemini Integration Spec

## Overview

Rewrite the LLM Gateway from OpenAI-centric format to a Gemini-native reverse proxy.
The gateway sits between the AI Knowledge Assistant and Google Gemini API, adding rate limiting,
semantic caching, circuit breaking, and usage analytics — transparent to the client app.

---

## Architecture

```
AI Knowledge Assistant (Python / google-genai SDK)
  │
  │  x-goog-api-key: gw-xxx
  │  POST /llm-gateway/v1/models/gemini-3.5-flash:generateContent
  │
  ▼
LLM Gateway (Java Spring Boot)
  ├─ Auth: validate gw-xxx from x-goog-api-key header
  ├─ Rate limit: per-key RPM + TPM check
  ├─ Cache: semantic lookup (chat) / hash lookup (embeddings)
  ├─ If cache miss:
  │    ├─ Circuit breaker check
  │    ├─ Strip /llm-gateway prefix
  │    ├─ Forward to https://generativelanguage.googleapis.com/v1/models/...
  │    ├─ Attach real Google API key as ?key= query param
  │    └─ Parse response: extract tokens, estimate cost
  ├─ Cache store (on miss)
  ├─ Record token usage for TPM
  ├─ Publish Kafka usage event
  └─ Return Google's response as-is to the client
```

---

## Google Gemini Endpoints Proxied

| Client calls (via gateway)                                          | Gateway forwards to                                                                  | Used by                        |
|---------------------------------------------------------------------|---------------------------------------------------------------------------------------|--------------------------------|
| `POST /llm-gateway/v1/models/{embed_model}:batchEmbedContents`     | `POST https://generativelanguage.googleapis.com/v1/models/{embed_model}:batchEmbedContents` | Search (query embed), Indexing (chunk embed) |
| `POST /llm-gateway/v1/models/{chat_model}:generateContent`         | `POST https://generativelanguage.googleapis.com/v1/models/{chat_model}:generateContent`     | Non-streaming chat, Summarization |
| `POST /llm-gateway/v1/models/{chat_model}:streamGenerateContent`   | `POST https://generativelanguage.googleapis.com/v1/models/{chat_model}:streamGenerateContent`| Streaming chat (RAG)           |

Current models used by the knowledge assistant:
- Embedding: `gemini-embedding-001` (768 dimensions)
- Chat: `gemini-3.5-flash`

---

## Request/Response Formats (Gemini native — passed through as-is)

### generateContent / streamGenerateContent

**Request body:**
```json
{
  "contents": [
    {
      "role": "user",
      "parts": [{ "text": "What is machine learning?" }]
    }
  ],
  "systemInstruction": {
    "parts": [{ "text": "You are a helpful assistant." }]
  },
  "generationConfig": {
    "temperature": 0.7,
    "maxOutputTokens": 1024
  }
}
```

**Response body (generateContent):**
```json
{
  "candidates": [
    {
      "content": {
        "parts": [{ "text": "Machine learning is..." }],
        "role": "model"
      },
      "finishReason": "STOP"
    }
  ],
  "usageMetadata": {
    "promptTokenCount": 12,
    "candidatesTokenCount": 85,
    "totalTokenCount": 97
  }
}
```

**Response body (streamGenerateContent):**
Server-Sent Events, each chunk:
```json
{
  "candidates": [
    {
      "content": {
        "parts": [{ "text": "Machine" }],
        "role": "model"
      }
    }
  ]
}
```
Final chunk includes `usageMetadata` with token counts.

### batchEmbedContents

**Request body:**
```json
{
  "requests": [
    {
      "model": "models/gemini-embedding-001",
      "content": { "parts": [{ "text": "some text to embed" }] },
      "outputDimensionality": 768
    }
  ]
}
```

**Response body:**
```json
{
  "embeddings": [
    {
      "values": [0.0123, -0.0456, ...]
    }
  ]
}
```

---

## Gateway — What the Proxy Extracts (does NOT modify)

The gateway reads these fields from the passthrough traffic for its internal features:

| Field                             | Extracted from          | Used for                              |
|-----------------------------------|-------------------------|---------------------------------------|
| Model name                        | URL path (`/models/{model}:action`) | Rate limiting, cost estimation, cache key, Kafka event |
| Request type                      | URL action (`:generateContent`, `:streamGenerateContent`, `:batchEmbedContents`) | Routing logic, cache strategy |
| Prompt text                       | Request `contents[].parts[].text` | Semantic cache key (chat requests)    |
| Embedding input texts             | Request `requests[].content.parts[].text` | Embedding cache key                   |
| Prompt token count                | Response `usageMetadata.promptTokenCount` | TPM rate limiting, cost estimation    |
| Output token count                | Response `usageMetadata.candidatesTokenCount` | TPM rate limiting, cost estimation    |
| Total token count                 | Response `usageMetadata.totalTokenCount` | Kafka usage event                     |
| Embedding vectors                 | Response `embeddings[].values` | Embedding cache store                 |

---

## Authentication

### Client → Gateway
- Header: `x-goog-api-key: gw-xxx` (sent automatically by google-genai SDK)
- The gateway's `ApiKeyFilter` validates `gw-xxx` against the `api_keys` table
- Existing `Authorization: Bearer gw-xxx` also accepted (backwards compat)

### Gateway → Google
- Query param: `?key=REAL_GOOGLE_API_KEY` appended by the gateway
- The real Google API key is stored in gateway's `application.yml`, never exposed to clients

---

## Rate Limiting

Existing per-key rate limiting applies to ALL proxied endpoints:

| Bucket             | Key                        | Check                   | Record                     |
|--------------------|----------------------------|-------------------------|----------------------------|
| Requests per minute | `ratelimit:req:{apiKeyId}` | Before forwarding       | On request arrival         |
| Tokens per minute   | `ratelimit:tok:{apiKeyId}` | —                       | After response (from `usageMetadata`) |

Embedding requests count against the same RPM/TPM budgets as chat requests.

---

## Caching

### Chat responses (semantic cache — exists, needs format update)
- **Key:** SHA-256 of prompt text + model (exact match) OR embedding similarity (semantic match)
- **Stored value:** Full Gemini response JSON (was OpenAI format, now Gemini format)
- **Applies to:** `generateContent` only (not streaming, not embeddings)
- **Embedding service for cache:** Still uses Ollama `nomic-embed-text` internally for computing similarity vectors

### Embedding responses (new — hash cache)
- **Key:** SHA-256 of input text + model + dimensionality
- **Stored value:** The embedding vector `float[]`
- **Applies to:** `batchEmbedContents`
- **Rationale:** Same document chunks re-embedded on re-index return identical vectors. Pure key-value, no semantic matching needed.

---

## Kafka Usage Events

Published after every proxied request. `UsageEvent` record shape stays the same:

```
apiKeyId, provider("gemini"), model, inputTokens, outputTokens,
estimatedCostUsd, latencyMs, cacheHit, fallbackUsed, status,
requestType("chat"|"chat-stream"|"embedding"), errorMessage, timestamp
```

Addition: `requestType` field to distinguish chat vs embedding in analytics.

---

## Cost Estimation (Gemini pricing)

| Model                  | Input (per 1M tokens) | Output (per 1M tokens) |
|------------------------|-----------------------|------------------------|
| gemini-3.5-flash       | $0.15                 | $0.60                  |
| gemini-embedding-001   | $0.00                 | N/A (free tier)        |

Configured in `application.yml` under `gateway.pricing.gemini`.

---

## Circuit Breaker

- Single breaker keyed by provider: `gemini`
- Triggers on: Google 5xx responses, timeouts
- Pass-through on: 4xx (client errors from Google)
- When OPEN: gateway returns 503 immediately, does not forward
- No failover to another provider (only using Gemini)

---

## LLM Gateway — Implementation Units

### Unit 1: Gemini request/response models
Java records for Gemini JSON: `GeminiGenerateRequest`, `GeminiGenerateResponse`,
`GeminiEmbedRequest`, `GeminiEmbedResponse`, and nested types.

### Unit 2: Gemini format parser
Utility service to extract prompt text, model name, token counts, request type
from Gemini-shaped request/response bodies and URL paths.

### Unit 3: Proxy controller
New controller at `/llm-gateway/**`. Strips prefix, forwards to Google host,
appends API key, handles streaming passthrough.

### Unit 4: Auth filter update
`ApiKeyFilter` reads `x-goog-api-key` header in addition to `Authorization: Bearer`.
`SecurityConfig` permits `/llm-gateway/**`.

### Unit 5: Rate limiting integration
`RateLimitFilter` intercepts `/llm-gateway/**`.
Post-response token recording from `usageMetadata`.

### Unit 6: Semantic cache adaptation
Update `RedisSemanticCache` to store/lookup Gemini-format responses.
New embedding response cache (hash-based).

### Unit 7: Kafka events + cost estimation
Add Gemini pricing to `CostEstimator`. Add `requestType` to `UsageEvent`.
Publish events after proxy response.

### Unit 8: Circuit breaker
Wrap proxy forwarding in Resilience4j breaker for "gemini" provider.

### Unit 9: Cleanup old OpenAI-centric code
Remove `OpenAiProvider`, `OllamaProvider`, `LlmProvider`, `ProviderRouter`,
`ChatCompletionController`, old `ChatRequest`/`ChatResponse` models.

### Unit 10: Dashboard API updates
`StatsController` reflects embedding vs chat breakdown.

---

## AI Knowledge Assistant — Changes

### Config (config.py)
```python
# Add
gateway_base_url: str = ""     # "http://localhost:8080/llm-gateway"
gateway_api_key: str = ""      # "gw-xxx"

# Keep (model names still needed)
gemini_embed_model: str = "gemini-embedding-001"
gemini_chat_model: str = "gemini-3.5-flash"
```

### GeminiProvider (gemini.py) — 1 line change
```python
# Before
self.client = genai.Client(api_key=api_key)

# After
self.client = genai.Client(
    api_key=gateway_api_key,
    http_options=types.HttpOptions(
        base_url=gateway_base_url,
        api_version='v1',
    ),
)
```

### Factory (factory.py) — pass new config values
```python
GeminiProvider(
    gateway_api_key=settings.gateway_api_key,
    gateway_base_url=settings.gateway_base_url,
    embed_model=settings.gemini_embed_model,
    chat_model=settings.gemini_chat_model,
)
```

### Everything else: UNCHANGED
- `embed()`, `embed_batch()`, `chat()` methods — no change
- `rag.py`, `search.py`, `summarization.py`, `indexing.py` — no change
- `base.py`, `ollama.py` — no change
- All API endpoints, schemas, models — no change
