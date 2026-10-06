# LLM Gateway — Design Specification

## Overview

A proxy service that sits between client applications and LLM providers (OpenAI, Ollama), exposing a unified OpenAI-compatible API. It handles authentication, rate limiting, semantic caching, circuit-breaker failover, and usage tracking. A React dashboard provides monitoring and admin controls.

**Primary use case:** Route an existing AI Personal Knowledge Assistant (currently using Ollama directly) through the gateway to gain rate limiting, caching, failover, and usage observability.

**Secondary use case:** Resume/portfolio project demonstrating system design patterns — rate limiter, distributed cache, circuit breaker, event pipeline, adapter pattern.

## Architecture

### Deployment Model

- **Development:** Local JVM for Spring Boot apps, Docker Compose for infrastructure (Redis, Kafka, Zookeeper, PostgreSQL, MongoDB).
- **Services:**
  - `gateway-service` — Main Spring Boot application. Handles all request processing, auth, rate limiting, caching, circuit breaking, and Kafka event publishing. Also exposes admin REST API for the dashboard.
  - `analytics-consumer` — Separate Spring Boot application. Kafka consumer that writes usage events to MongoDB.
  - `dashboard` — React SPA (Vite). Calls the gateway's admin API endpoints.

### System Diagram

```
                         ┌──────────────────┐
    Client App ─────────►│  Gateway Service  │──────► OpenAI API
    (Knowledge           │  (Spring Boot)    │──────► Ollama (local)
     Assistant)          │                   │
                         │  Auth Filter      │
                         │  Rate Limiter ◄──►│──► Redis (buckets)
                         │  Semantic Cache◄─►│──► Redis (RediSearch)
                         │  Circuit Breaker  │      + Ollama (embed)
                         │  Admin API ◄─────►│──► Postgres (keys/config)
                         │  Kafka Producer   │
                         └────────┬──────────┘
                                  │ Kafka
                         ┌────────▼──────────┐
                         │ Analytics Consumer │
                         │  (Spring Boot)     │──► MongoDB (events)
                         └───────────────────┘

                         ┌───────────────────┐
                         │   React Dashboard  │
                         │   (Vite SPA)       │──► Gateway Admin API
                         └───────────────────┘
```

### Technology Stack

| Component | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3 |
| Rate Limiting & Cache | Redis 7 + RediSearch module |
| Embeddings | Ollama (nomic-embed-text, local) |
| Circuit Breaker | Resilience4j |
| Event Streaming | Apache Kafka |
| User/Key Storage | PostgreSQL |
| Usage/Analytics Storage | MongoDB |
| Dashboard | React + Vite |
| Charts | Recharts |
| Infrastructure | Docker Compose |

## Request Flow

```
Client sends POST /v1/chat/completions
  │
  ├─ 1. Auth Filter: Extract API key from Authorization header,
  │     validate against Postgres. Reject 401 if invalid.
  │
  ├─ 2. Rate Limiter: Check Redis token bucket for this key.
  │     Reject 429 if exhausted.
  │
  ├─ 3. Cache Lookup: Embed the prompt via Ollama,
  │     search RediSearch for similar vectors (cosine > 0.95 threshold).
  │     If hit → return cached response, skip provider call.
  │
  ├─ 4. Provider Router: Pick provider based on request's model field.
  │     "gpt-4o" → OpenAI adapter. "llama3" → Ollama adapter.
  │     Both adapt to/from the OpenAI wire format.
  │
  ├─ 5. Circuit Breaker (Resilience4j): Wraps each provider call.
  │     If provider is OPEN (failing) → fall back to the other provider.
  │     Maps the model to the best equivalent on the fallback provider.
  │
  ├─ 6. Response: Stream SSE chunks back to client.
  │     On completion, cache the full response in Redis with its embedding.
  │
  └─ 7. Usage Event: Publish to Kafka topic "llm-usage-events"
        {apiKeyId, provider, model, inputTokens, outputTokens,
         cost, latencyMs, cacheHit, timestamp}
```

### Streaming + Caching

- The gateway streams SSE chunks to the client in real-time (no added latency).
- It also buffers the full response internally.
- Once streaming completes, it stores the full response in the cache with its embedding vector.
- Cache hits return the full response at once (not re-streamed).

## API Surface

### Client API (OpenAI-compatible)

```
POST /v1/chat/completions
Authorization: Bearer gw-xxxxxxxxxxxx

Request body: OpenAI chat completion format
{
  "model": "gpt-4o",
  "messages": [{"role": "user", "content": "..."}],
  "stream": true/false,
  "temperature": 0.7,
  ...
}

Response: OpenAI chat completion format (or SSE stream)
```

### Admin API

```
GET    /api/admin/stats                  → overview numbers
GET    /api/admin/usage?days=7&keyId=    → usage events from MongoDB
GET    /api/admin/providers              → provider health + circuit state
PUT    /api/admin/providers/{id}/toggle  → enable/disable provider
PUT    /api/admin/providers/{id}/models  → update model mappings

POST   /api/admin/keys                   → create API key (returns plaintext once)
GET    /api/admin/keys                   → list all keys
GET    /api/admin/keys/{id}              → key details + usage stats
PUT    /api/admin/keys/{id}              → update name, rate limits, enabled
DELETE /api/admin/keys/{id}              → revoke key

Auth: Basic auth with ADMIN_PASSWORD environment variable
```

## Data Models

### PostgreSQL — API Keys & Config

```sql
CREATE TABLE api_keys (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    key_hash      VARCHAR(255) UNIQUE NOT NULL,  -- bcrypt hash
    name          VARCHAR(100) NOT NULL,
    owner         VARCHAR(100) NOT NULL,
    rate_limit_rpm    INT NOT NULL DEFAULT 60,        -- requests per minute
    rate_limit_tpm    INT NOT NULL DEFAULT 100000,    -- tokens per minute
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    last_used_at  TIMESTAMP
);

CREATE TABLE provider_config (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_name     VARCHAR(50) UNIQUE NOT NULL,  -- "openai", "ollama"
    base_url          VARCHAR(255) NOT NULL,
    api_key_encrypted VARCHAR(500),                 -- encrypted, nullable for Ollama
    enabled           BOOLEAN NOT NULL DEFAULT TRUE,
    priority          INT NOT NULL DEFAULT 0,       -- failover order
    model_mappings    JSONB NOT NULL DEFAULT '{}'    -- {"gpt-4o": "llama3"}
);
```

### MongoDB — Usage Events

```
Collection: usage_events

{
    _id:              ObjectId,
    api_key_id:       UUID,
    provider:         String,       // "openai" | "ollama"
    model:            String,
    input_tokens:     Int,
    output_tokens:    Int,
    estimated_cost_usd: Double,
    latency_ms:       Long,
    cache_hit:        Boolean,
    fallback_used:    Boolean,
    status:           String,       // "success" | "error"
    error_message:    String?,
    timestamp:        Date
}

Indexes:
  - { timestamp: 1 }             — TTL index, expire after 90 days
  - { api_key_id: 1, timestamp: -1 } — per-key queries
  - { provider: 1, timestamp: -1 }    — per-provider queries
```

### Redis — Rate Limiting

```
Request count bucket:
  Key:  "ratelimit:req:{apiKeyId}"
  Type: String (counter)
  TTL:  60 seconds (auto-reset each minute)

Token budget bucket:
  Key:  "ratelimit:tok:{apiKeyId}"
  Type: String (counter)
  TTL:  60 seconds

Both buckets must pass for a request to proceed.
Operations are atomic via Lua script.
```

### Redis — Semantic Cache (RediSearch)

```
Index: idx:semantic_cache

Key pattern: "cache:{sha256_of_prompt}"

Fields:
  embedding     VECTOR    768 dims (nomic-embed-text), cosine similarity
  prompt_hash   TEXT      SHA-256 of exact prompt for fast exact-match
  model         TAG       only serve hits for same model family
  response      TEXT      full JSON response body
  created_at    NUMERIC

Similarity threshold: cosine > 0.95
Default TTL: 1 hour (configurable)
Cache scoping: hits filtered by model tag — a gpt-4o cached response is not served for llama3 requests.
```

## Rate Limiting

### Algorithm: Dual Token Bucket

Two buckets per API key, both must pass:

1. **Request count bucket** — `INCR` with 60s TTL. Tracks requests per minute.
2. **Token budget bucket** — `INCRBY` with 60s TTL. Tracks input+output tokens per minute.

Request count is checked before the provider call. Token budget is deducted after the response (when token count is known).

### Response Headers (OpenAI-compatible)

```
X-RateLimit-Limit-Requests: 60
X-RateLimit-Remaining-Requests: 45
X-RateLimit-Limit-Tokens: 100000
X-RateLimit-Remaining-Tokens: 82000
X-RateLimit-Reset-Requests: 32s
X-RateLimit-Reset-Tokens: 32s
```

### Configuration

Rate limits are configurable per API key via Postgres. Changes take effect immediately. The rate limiter reads config from Postgres with a short Redis cache (60s TTL) to avoid hitting Postgres on every request.

## Circuit Breaker & Failover

### Resilience4j Configuration (per provider)

```
Failure rate threshold:      50% (over sliding window of last 10 calls)
Slow call duration threshold: 10 seconds
Slow call rate threshold:    80%
Wait duration in open state: 30 seconds
Permitted calls in half-open: 3
```

### Failover Logic

1. Router resolves model → primary provider.
2. Check circuit breaker state for that provider.
3. If CLOSED → call normally.
4. If OPEN → skip to fallback provider, map model via `model_mappings`.
5. If HALF_OPEN → attempt call; breaker decides based on result.
6. If all providers down → return 503.

### Failure Classification

**Triggers failover:**
- HTTP 5xx from provider
- Connection timeout (5s)
- Read timeout (30s)
- Malformed response

**Does NOT trigger failover (pass through to client):**
- HTTP 400/401/403 — client error
- HTTP 429 — provider rate limit (pass through)

### Response Headers for Failover

```
X-LLM-Gateway-Fallback: true       — present when fallback was used
X-LLM-Gateway-Original-Provider: openai
X-LLM-Gateway-Fallback-Provider: ollama
```

## Event Pipeline

### Kafka

```
Topic: llm-usage-events
Partitions: 3
Replication: 1 (dev setup)
Retention: 7 days

Event schema (JSON):
{
    "apiKeyId": "uuid",
    "provider": "openai",
    "model": "gpt-4o",
    "inputTokens": 150,
    "outputTokens": 500,
    "estimatedCostUsd": 0.0065,
    "latencyMs": 1200,
    "cacheHit": false,
    "fallbackUsed": false,
    "status": "success",
    "errorMessage": null,
    "timestamp": "2026-10-06T12:00:00Z"
}
```

### Analytics Consumer

- Separate Spring Boot application with `@KafkaListener`.
- Consumes from `llm-usage-events` topic.
- Writes each event as a document to MongoDB `usage_events` collection.
- Consumer group: `analytics-consumer-group`.

## Dashboard

React SPA (Vite) with 4 pages. Calls the gateway's admin API.

### Pages

**1. Overview (`/`)**
- Request volume over time (line chart, 24h / 7d / 30d toggles)
- Total cost breakdown by provider (bar chart)
- Cache hit rate (big number + trend arrow)
- Active API keys count
- Error rate percentage

**2. Cache Analytics (`/cache`)**
- Cache hit rate over time (line chart)
- Estimated cost saved (hits x average cost per call)
- Cache size (entry count, memory)
- Recent cache hits/misses table with prompt preview
- Flush cache button

**3. Providers (`/providers`)**
- Per-provider card:
  - Status badge: UP / DEGRADED / DOWN
  - Circuit breaker state: CLOSED / OPEN / HALF_OPEN (color-coded)
  - p50 / p95 / p99 latency
  - Error rate, request count
- Enable/disable toggle per provider
- Model mapping editor

**4. API Keys (`/keys`)**
- Table: name, owner, created, last used, status
- Create key (shows plaintext once)
- Revoke / disable key
- Per-key rate limit config (RPM, TPM)
- Per-key usage stats

### Data Fetching

- Polling every 30 seconds via admin API.
- No WebSocket for v1.
- Dashboard auth: basic auth with `ADMIN_PASSWORD` env var.

## Package Structure

```
llm-gateway/
├── gateway-service/
│   └── src/main/java/com/llmgateway/
│       ├── GatewayApplication.java
│       ├── config/
│       │   ├── RedisConfig.java
│       │   ├── KafkaProducerConfig.java
│       │   ├── ResilienceConfig.java
│       │   └── SecurityConfig.java
│       ├── auth/
│       │   ├── ApiKeyFilter.java
│       │   ├── ApiKeyService.java
│       │   └── ApiKeyRepository.java
│       ├── ratelimit/
│       │   ├── RateLimiter.java
│       │   └── RedisTokenBucketRateLimiter.java
│       ├── cache/
│       │   ├── SemanticCache.java
│       │   ├── RedisSemanticCache.java
│       │   └── EmbeddingService.java
│       ├── provider/
│       │   ├── LlmProvider.java
│       │   ├── OpenAiProvider.java
│       │   ├── OllamaProvider.java
│       │   └── ProviderRouter.java
│       ├── streaming/
│       │   └── SseResponseHandler.java
│       ├── events/
│       │   ├── UsageEvent.java
│       │   └── KafkaUsagePublisher.java
│       ├── controller/
│       │   ├── ChatCompletionController.java
│       │   └── AdminController.java
│       └── model/
│           ├── ChatRequest.java
│           ├── ChatResponse.java
│           └── ApiKey.java
│
├── analytics-consumer/
│   └── src/main/java/com/llmgateway/analytics/
│       ├── AnalyticsConsumerApplication.java
│       ├── KafkaUsageConsumer.java
│       ├── UsageEventDocument.java
│       └── UsageRepository.java
│
├── dashboard/
│   ├── package.json
│   ├── vite.config.ts
│   └── src/
│       ├── App.tsx
│       ├── pages/
│       │   ├── Overview.tsx
│       │   ├── CacheAnalytics.tsx
│       │   ├── Providers.tsx
│       │   └── ApiKeys.tsx
│       ├── components/
│       │   ├── charts/
│       │   └── layout/
│       ├── api/
│       │   └── client.ts
│       └── types/
│
├── docker/
│   └── docker-compose.yml
│
└── docs/
    └── superpowers/
        └── specs/
```

## Infrastructure (Docker Compose)

```yaml
Services:
  redis:        redis/redis-stack (includes RediSearch module)
                port 6379
  kafka:        confluent kafka
                port 9092
  zookeeper:    confluent zookeeper (required by kafka)
                port 2181
  postgres:     postgres:16
                port 5432
  mongodb:      mongo:7
                port 27017
```

## Resume Metrics to Track

These are the numbers the project is built to produce:

1. **Cache hit rate** — percentage of requests served from semantic cache
2. **Cost saved** — dollars not spent on provider calls due to cache hits
3. **p95 latency overhead** — milliseconds added by the gateway vs direct provider call
4. **Failover success rate** — "killed OpenAI, 0 failed requests" demo
5. **Requests per second** — throughput the gateway sustains under load

## Cost Estimation

The gateway estimates cost per request using a static pricing table:

```
Pricing table (configurable in application.yml):
  openai:
    gpt-4o:       { input: 2.50, output: 10.00 }    # per 1M tokens
    gpt-4o-mini:  { input: 0.15, output: 0.60 }
  ollama:
    llama3:       { input: 0.00, output: 0.00 }     # local, free
```

Cost = (inputTokens * inputPrice / 1_000_000) + (outputTokens * outputPrice / 1_000_000)

This is an estimate — actual OpenAI billing may differ slightly. The value is in tracking relative costs and demonstrating savings from caching.

## Future Extensions (not in v1)

- Claude provider adapter (third provider)
- WebSocket real-time dashboard updates
- Response quality monitoring (compare cached vs fresh responses)
- Multi-model routing (route based on prompt complexity)
- Prompt transformation pipeline (sanitization, PII redaction)
